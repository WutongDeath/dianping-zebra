package com.dianping.zebra.admin.job.executor;

import com.dianping.cat.Cat;
import com.dianping.zebra.admin.entity.ShardDumpDbEntity;
import com.dianping.zebra.admin.entity.ShardDumpTaskEntity;
import com.dianping.zebra.admin.service.ShardDumpService;
import com.dianping.zebra.admin.util.ProcessBuilderWrapper;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Dozer @ 2015-02
 * mail@dozer.cc
 * http://www.dozer.cc
 */
public class ShardDumpTaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ShardDumpTaskExecutor.class);

    private static final Charset DEFAULT_CJARSET = Charset.forName("utf8");

    private ShardDumpService shardDumpService;

    private static final Pattern BINLOG_PATTERN = Pattern
            .compile(".*MASTER_LOG_FILE='([^']+)', MASTER_LOG_POS=(\\d+).*");

    protected final ShardDumpTaskEntity task;

    protected final String dumpOutputDir;

    protected static final long FINISH_INDEX = Long.MIN_VALUE;

    protected volatile ShardDumpDbEntity srcDBInstance;

    protected volatile ShardDumpDbEntity dstDBInstance;

    protected final BlockingQueue<Long> waitForLoadQueue = new LinkedBlockingQueue<Long>(5);

    protected Thread dumpWorker;

    protected Thread loadWorker;

    public ShardDumpTaskExecutor(ShardDumpTaskEntity task) {
        checkNotNull(task, "task");
        checkNotNull(task.getTableName(), "task.tableName");
        checkNotNull(task.getIndexColumnName(), "task.indexColumnName");

        this.task = task;
        //        this.dumpOutputDir = (SyncServerConfig.getInstance() == null ?
        //                "/tmp/" :
        //                SyncServerConfig.getInstance().getTempDir() + "/dump/") + task.getName() + "/";
        this.dumpOutputDir = "/tmp/";
    }

    private boolean checkFinish() {
        if (this.task.getIndexKey() == FINISH_INDEX) {
            //            loadStatus.setStatus(Status.SUCCESS);
            //            dumpStatus.setStatus(Status.SUCCESS);
            return true;
        }
        return false;
    }

    public void init() {
        if (checkFinish()) {
            return;
        }

        this.dumpWorker = new Thread(new DumpWorker());
        this.loadWorker = new Thread(new LoadWorker());
        createOutPutDir();
    }

    public void destroy() {

    }

    protected synchronized void saveTask() {
        shardDumpService.updateTaskStatus(this.task);
    }

    protected void createOutPutDir() {
        File theDir = new File(this.dumpOutputDir);
        if (!theDir.exists()) {
            theDir.mkdirs();
        }
    }

    protected String getDumpFile(long index) {
        return String.format("%s%d-%d.dump.sql", dumpOutputDir, task.getShardRule().hashCode(), index);
    }

    class DumpWorker implements Runnable {
        protected long lastIndex;

        public DumpWorker() {
            this.lastIndex = task.getIndexKey();
        }

        protected boolean checkHasData(long index) {
            FileInputStream reader = null;
            boolean hasData = false;
            File file = null;
            try {
                file = new File(getDumpFile(index));
                if (file.length() >= 1024 * 8) {
                    hasData = true;
                    return hasData;
                }
                hasData = FileUtils.readFileToString(file).contains("INSERT");
                return hasData;
            } catch (IOException e) {
                hasData = false;
                return hasData;
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignoire) {
                    }
                }
                if (!hasData && file != null) {
                    file.delete();
                }
            }
        }

        @Override
        public void run() {
            //            dumpStatus.setStatus(Status.RUNNING);

            while (true) {
                long nextIndex = increaseIndex();

                try {
                    String output = mysqldump(this.lastIndex, nextIndex);
                    if (!Strings.isNullOrEmpty(output)) {
                        //hack,ignore this warning
                        if (!output
                                .contains("Warning: Using a password on the command line interface can be insecure.")) {
                            throw new IOException(output);
                        }
                    }

                    if (!checkHasData(this.lastIndex)) {
                        //                        dumpStatus.setPercent(ShardSyncTaskState.PERCENT_MAX);
                        waitForLoadQueue.put(FINISH_INDEX);
                        break;
                    }

                    output = convertTableName(this.lastIndex);
                    if (!Strings.isNullOrEmpty(output)) {
                        throw new IOException(output);
                    }

                    checkAndUpdateBinlogInfo(this.lastIndex);

                    //                    dumpStatus.setPercent((int) (this.lastIndex * 100 / task.getMaxKey()));
                    waitForLoadQueue.put(lastIndex);
                    this.lastIndex = nextIndex;

                } catch (InterruptedException e) {
                    //                    dumpStatus.setStatus(Status.SUSPENDED);
                    break;
                } catch (Exception e) {
                    String msg = "Dump Failed!";
                    logger.error(msg, e);
                    Cat.logError(msg, e);
                    //                    dumpStatus.setStatus(Status.FAILED);
                    break;
                }
            }
        }

        protected long increaseIndex() {
            return this.lastIndex + task.getIndexIncrease();
        }

        protected String mysqldump(long lastIndex, long nextIndex) throws IOException, InterruptedException {
            checkNotNull(srcDBInstance, "srcDBInstance");

            List<String> cmdlist = new ArrayList<String>();

            //todo: config mysql dump in config
            cmdlist.add("mysqldump");
            cmdlist.add("--host=" + srcDBInstance.getHost());
            cmdlist.add("--port=" + srcDBInstance.getPort());
            cmdlist.add("--user=" + srcDBInstance.getUsername());
            cmdlist.add("--password=" + srcDBInstance.getPassword());
            cmdlist.add("--where=" + String
                    .format("%s AND %s > %d AND %s <= %d AND %s <= %d", task.getShardRule(), task.getIndexColumnName(),
                            lastIndex, task.getIndexColumnName(), nextIndex, task.getIndexColumnName(),
                            task.getMaxKey()));

            cmdlist.addAll(task.getOptions());
            cmdlist.add("--result-file=" + getDumpFile(lastIndex));
            cmdlist.add(task.getDataBase());
            cmdlist.add(task.getTableName());

            return executeByProcessBuilder(cmdlist);
        }

        protected String convertTableName(long index) throws IOException, InterruptedException {
            List<String> cmdlist = new ArrayList<String>();
            cmdlist.add("perl");
            cmdlist.add("-i");
            cmdlist.add("-p");
            cmdlist.add("-e");
            cmdlist.add(
                    "s/(^INSERT )(INTO )(`)([^`]+)(`)(.*$)/\\1IGNORE \\2\\3" + task.getTargetTableName() + "\\5\\6/g");
            cmdlist.add(getDumpFile(index));
            return executeByProcessBuilder(cmdlist);
        }

        protected void checkAndUpdateBinlogInfo(long index) throws IOException {
            //-- CHANGE MASTER TO MASTER_LOG_FILE='mysqlbin-log.000010', MASTER_LOG_POS=91841517;
            String firstLine = readFirstLine(index);

            Matcher result = BINLOG_PATTERN.matcher(firstLine);
            if (!result.matches() || result.groupCount() != 2) {
                throw new IOException("BINLOG read failed! " + firstLine);
            }

            String binlog = result.group(1);
            long position = Long.parseLong(result.group(2));

            checkAndUpdateBinlogInfo(binlog, position);
        }

        protected void checkAndUpdateBinlogInfo(String binlog, long position) throws IOException {
            boolean needToSet = false;

            if (Strings.isNullOrEmpty(task.getBinlogFile())) {
                needToSet = true;
            } else {
                int oldBinlogIndex = getBinlogIndex(task.getBinlogFile());
                int newBinlogIndex = getBinlogIndex(binlog);

                if (newBinlogIndex < oldBinlogIndex || (newBinlogIndex == oldBinlogIndex && position < task
                        .getBinlogPos())) {
                    needToSet = true;
                }
            }

            if (needToSet) {
                task.setBinlogFile(binlog);
                task.setBinlogPos(position);
                saveTask();
            }
        }

        protected int getBinlogIndex(String binlog) throws IOException {
            //mysql-bin.0000001
            int index = binlog.lastIndexOf(".");
            if (index < 0 || index == binlog.length() - 1) {
                throw new IOException("binlog file name error: " + binlog);
            }
            return Integer.parseInt(binlog.substring(index + 1));
        }

        protected String readFirstLine(long index) throws IOException {
            return Files.readFirstLine(new File(getDumpFile(index)), DEFAULT_CJARSET);
        }
    }

    class LoadWorker implements Runnable {
        protected void cleanUp(long index) {
            new File(getDumpFile(index)).delete();
            task.setIndexKey(index);
            saveTask();
        }

        @Override
        public void run() {
            //            loadStatus.setStatus(Status.RUNNING);

            while (true) {
                try {
                    Long index = waitForLoadQueue.take();
                    if (index == FINISH_INDEX) {
                        //finish
                        cleanUp(index);
                        cleanup();
                        //                        loadStatus.setPercent(ShardSyncTaskState.PERCENT_MAX);
                        break;
                    }

                    String output = mysqlload(index);
                    if (!Strings.isNullOrEmpty(output)) {
                        //hack,ignore this warning
                        if (!output
                                .contains("Warning: Using a password on the command line interface can be insecure.")) {
                            throw new IOException(output);
                        }
                    }
                    cleanUp(index);
                    //                    loadStatus.setPercent((int) (index * 100 / task.getMaxKey()));
                } catch (InterruptedException e) {
                    //                    loadStatus.setStatus(Status.SUSPENDED);
                    break;
                } catch (Exception e) {
                    String msg = "Load Failed!";
                    logger.error(msg, e);
                    Cat.logError(msg, e);
                    //                    loadStatus.setStatus(Status.FAILED);
                    break;
                }
            }
        }

        protected String mysqlload(long index) throws IOException, InterruptedException {
            checkNotNull(dstDBInstance, "dstDBInstance");

            List<String> cmdlist = new ArrayList<String>();
            cmdlist.add("mysql -f --default-character-set=utf8");
            cmdlist.add("'--database=" + task.getTargetDataBase() + "'");
            cmdlist.add("'--user=" + dstDBInstance.getUsername() + "'");
            cmdlist.add("'--host=" + dstDBInstance.getHost() + "'");
            cmdlist.add("'--port=" + dstDBInstance.getPort() + "'");
            cmdlist.add("'--password=" + dstDBInstance.getPassword() + "'");
            cmdlist.add("< '" + getDumpFile(index) + "'");

            return executeByProcessBuilder(Lists.newArrayList("sh", "-c", Joiner.on(" ").join(cmdlist)));
        }

    }

    protected String executeByProcessBuilder(List<String> cmd) throws IOException, InterruptedException {
        logger.debug("execute shell script, cmd is: {}", Joiner.on(" ").join(cmd));
        ProcessBuilderWrapper pbd = new ProcessBuilderWrapper(cmd);
        logger.debug("Command has terminated with status: {}", pbd.getStatus());
        if (!Strings.isNullOrEmpty(pbd.getInfos())) {
            logger.debug("Output:\n{}", pbd.getInfos());
        }
        return pbd.getErrors();
    }

    public void start() {
        if (checkFinish()) {
            return;
        }

        dumpWorker.start();
        loadWorker.start();
    }

    public ShardDumpTaskEntity getTask() {
        return this.task;
    }

    /*
    @Override
    public ShardSyncTaskState getTaskState() {
        checkFinish();

        ShardSyncTaskState status = new ShardSyncTaskState();
        status.setTaskName(this.task.getName());

        if (Status.SUCCESS.equals(dumpStatus.getStatus()) && Status.SUCCESS.equals(loadStatus.getStatus())) {
            status.setStatus(Status.SUCCESS);
            status.setPercent(ShardSyncTaskState.PERCENT_MAX);
            return status;
        }

        if (!dumpStatus.getStatus().equals(Status.RUNNING)) {
            status.setStatus(dumpStatus.getStatus());
        } else if (!loadStatus.getStatus().equals(Status.RUNNING)) {
            status.setStatus(loadStatus.getStatus());
        } else {
            status.setStatus(Status.RUNNING);
        }

        status.setPercent((dumpStatus.getPercent() + loadStatus.getPercent()) / 2);
        return status;
    }

    @Override
    public void setTaskState(ShardSyncTaskState taskState) {

    }*/

    public void stop() {
        dumpWorker.interrupt();
        loadWorker.interrupt();

        while (!dumpWorker.isInterrupted() || loadWorker.isInterrupted()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        cleanup();
    }

    public void cleanup() {
        try {
            FileUtils.deleteDirectory(new File(dumpOutputDir));
        } catch (IOException e) {
        }
    }

    public void setSrcDBInstance(ShardDumpDbEntity srcDBInstance) {
        this.srcDBInstance = srcDBInstance;
    }

    public void setDstDBInstance(ShardDumpDbEntity dstDBInstance) {
        this.dstDBInstance = dstDBInstance;
    }

    public void setShardDumpService(ShardDumpService shardDumpService) {
        this.shardDumpService = shardDumpService;
    }
}
