package com.dianping.zebra.admin.job.executor;

import com.dianping.cat.Cat;
import com.dianping.lion.EnvZooKeeperConfig;
import com.dianping.lion.client.ConfigCache;
import com.dianping.lion.client.LionException;
import com.dianping.puma.api.ConfigurationBuilder;
import com.dianping.puma.api.EventListener;
import com.dianping.puma.api.PumaClient;
import com.dianping.puma.core.constant.SubscribeConstant;
import com.dianping.puma.core.event.ChangedEvent;
import com.dianping.puma.core.event.RowChangedEvent;
import com.dianping.zebra.admin.entity.ShardSyncTaskEntity;
import com.dianping.zebra.config.LionKey;
import com.dianping.zebra.group.config.datasource.entity.DataSourceConfig;
import com.dianping.zebra.group.config.datasource.entity.GroupDataSourceConfig;
import com.dianping.zebra.group.jdbc.GroupDataSource;
import com.dianping.zebra.group.router.RouterType;
import com.dianping.zebra.shard.config.RouterRuleConfig;
import com.dianping.zebra.shard.config.TableShardDimensionConfig;
import com.dianping.zebra.shard.config.TableShardRuleConfig;
import com.dianping.zebra.shard.router.*;
import com.dianping.zebra.shard.router.rule.DimensionRule;
import com.dianping.zebra.shard.router.rule.DimensionRuleImpl;
import com.dianping.zebra.shard.router.rule.RouterRule;
import com.dianping.zebra.shard.router.rule.TableShardRule;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import org.apache.commons.lang3.SerializationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Dozer @ 6/8/15
 * mail@dozer.cc
 * http://www.dozer.cc
 */
public class ShardSyncTaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ShardSyncTaskExecutor.class);

    protected ShardSyncTaskEntity task;

    private volatile boolean inited = false;

    //    protected ShardSyncTaskState status;

    private ConfigCache configCache;

    protected GroupDataSource originDataSource;

    private Map<String, PumaClient> pumaClientList = new ConcurrentHashMap<String, PumaClient>();

    protected TableShardRuleConfig tableShardRuleConfigOrigin;

    protected List<TableShardRuleConfig> tableShardRuleConfigList = new CopyOnWriteArrayList<TableShardRuleConfig>();

    protected String originDsJdbcRef;

    protected RouterRule routerRuleOrigin;

    protected List<RouterRule> routerRuleList = new CopyOnWriteArrayList<RouterRule>();

    protected ShardRouter routerForMigrate;

    protected List<ShardRouter> routerList = new CopyOnWriteArrayList<ShardRouter>();

    private static final Pattern JDBC_URL_PATTERN = Pattern.compile("jdbc:mysql://([^:]+):\\d+/([^\\?]+).*");

    public ShardSyncTaskExecutor(ShardSyncTaskEntity task) {
        try {
            //            this.status = new ShardSyncTaskState();
            //            this.status.setStatus(Status.INITIALIZING);

            checkNotNull(task, "task");
            checkNotNull(task.getRuleName(), "task.ruleName");
            checkNotNull(task.getTableName(), "task.tableName");
            this.task = task;

            //            this.status.setTaskName(task.getName());

            this.configCache = ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress());
        } catch (Exception e) {
            Cat.logError("Shard Sync Task Init Failed!", e);
            //            this.status.setStatus(Status.FAILED);
        }
    }

    public void init() {
        try {
            checkNotNull(configCache, "configCache");
            initAndConvertConfig();
            initRouterRule();
            initDataSources();
            initRouter();
            initPumaClient();

            inited = true;
        } catch (Exception exp) {
            //            this.status.setStatus(Status.FAILED);
        }
    }

    class Processor implements EventListener {
        protected volatile int tryTimes = 0;

        protected final int MAX_TRY_TIMES = 2;

        private final String name;

        private final List<ShardRouter> routers;

        public Processor(String name, List<ShardRouter> routers) {
            this.name = name;
            this.routers = routers;
        }

        @Override
        public void onEvent(ChangedEvent event) throws Exception {
            tryTimes++;
            onEventInternal(event);
            tryTimes = 0;
        }

        protected void onEventInternal(ChangedEvent event) throws Exception {
            if (!(event instanceof RowChangedEvent)) {
                return;
            }
            RowChangedEvent rowEvent = (RowChangedEvent) event;
            if (rowEvent.isTransactionBegin() || rowEvent.isTransactionCommit()) {
                return;
            }

            String tempSql;
            List<Object> args;
            rowEvent.setTable(task.getTableName());
            rowEvent.setDatabase("");

            tempSql = rowChangedEventToSql(rowEvent);
            args = rowChangedEventToArgs(rowEvent);

            if (Strings.isNullOrEmpty(tempSql)) {
                return;
            }

            for (ShardRouter router : routers) {
                RouterResult routerTarget = router.router(tempSql, args);

                for (RouterTarget targetedSql : routerTarget.getTargetedSqls()) {
                    JdbcTemplate jdbcTemplate = new JdbcTemplate(
                        DataSourceRepository.getDataSource(targetedSql.getDataSourceName()));
                    for (String sql : targetedSql.getSqls()) {
                        jdbcTemplate.update(replaceInsert(sql), args.toArray());
                    }
                }
            }
        }

        protected String replaceInsert(String sql) {
            if (sql != null && sql.startsWith("INSERT")) {
                sql = "REPLACE" + sql.substring(6);
            }
            return sql;
        }

        protected List<Object> rowChangedEventToArgs(RowChangedEvent event) {
            int actionType = event.getActionType();
            Map<String, RowChangedEvent.ColumnInfo> columnMap = event.getColumns();
            List<Object> args = new ArrayList<Object>();
            switch (actionType) {
            case RowChangedEvent.INSERT:
            case RowChangedEvent.UPDATE:
                for (Map.Entry<String, RowChangedEvent.ColumnInfo> columnName2ColumnInfo : columnMap.entrySet()) {
                    args.add(columnName2ColumnInfo.getValue().getNewValue());
                }
                break;
            case RowChangedEvent.DELETE:
                for (Map.Entry<String, RowChangedEvent.ColumnInfo> columnName2ColumnInfo : columnMap.entrySet()) {
                    if (columnName2ColumnInfo.getValue().getOldValue() != null) {
                        args.add(columnName2ColumnInfo.getValue().getOldValue());
                    }
                }
                break;
            }
            return args;
        }

        protected String rowChangedEventToSql(RowChangedEvent event) {
            String sql = null;
            int actionType = event.getActionType();
            switch (actionType) {
            case RowChangedEvent.INSERT:
            case RowChangedEvent.UPDATE:
                //sql = SqlBuildUtil.buildSql(event, "/sql_template_shard/insertSql.vm");
                break;
            case RowChangedEvent.DELETE:
                //sql = SqlBuildUtil.buildSql(event, "/sql_template_shard/deleteSql.vm");
                break;
            }

            return sql;
        }

        @Override
        public boolean onException(ChangedEvent event, Exception e) {
            logException(event, e);
            return tryTimes >= MAX_TRY_TIMES;
        }

        public void logException(ChangedEvent event, Exception exp) {
            String msg = String.format("Name: %s Event: %s TryTimes: %s", this.name, event.toString(), tryTimes);
            Cat.logError(msg, exp);
            logger.error(msg, exp);
        }

        @Override
        public void onConnectException(Exception e) {
            Cat.logError("Client Connect Error:" + this.name, e);
        }

        @Override
        public void onConnected() {
            logger.info("{} connected to the server", this.name);
        }

        @Override
        public void onSkipEvent(ChangedEvent event) {

        }
    }

    protected void initRouterRule() {
        RouterRuleConfig routerRuleConfig = new RouterRuleConfig();
        routerRuleConfig.setTableShardConfigs(Lists.newArrayList(tableShardRuleConfigOrigin));
        this.routerRuleOrigin = RouterRuleBuilder.build(routerRuleConfig);

        if (task.getType() == ShardSyncTaskEntity.SYNC_TASK) {
            for (TableShardRuleConfig config : tableShardRuleConfigList) {
                RouterRuleConfig tempRouterRuleConfig = new RouterRuleConfig();
                tempRouterRuleConfig.setTableShardConfigs(Lists.newArrayList(config));
                routerRuleList.add(RouterRuleBuilder.build(tempRouterRuleConfig));
            }
        }
    }

    protected void initRouter() {
        if (task.getType() == ShardSyncTaskEntity.MIGRATE_TASK) {
            ShardRouterImpl routerForMigrate = new ShardRouterImpl();
            routerForMigrate.setRouterRule(routerRuleOrigin);
            this.routerForMigrate = routerForMigrate;
            this.routerForMigrate.init();
        } else if (task.getType() == ShardSyncTaskEntity.SYNC_TASK) {
            for (RouterRule routerRule : routerRuleList) {
                ShardRouterImpl tempRouter = new ShardRouterImpl();
                tempRouter.setRouterRule(routerRule);
                tempRouter.init();

                routerList.add(tempRouter);
            }
        }
    }

    protected void initDataSources() {
        if (task.getType() == ShardSyncTaskEntity.MIGRATE_TASK) {
            this.originDataSource = initGroupDataSource(originDsJdbcRef);
        }

        TableShardRule tableShardRule = routerRuleOrigin.getTableShardRules().get(task.getTableName());
        for (DimensionRule dimensionRule : tableShardRule.getDimensionRules()) {
            DimensionRuleImpl dimensionRuleImpl = (DimensionRuleImpl) dimensionRule;

            if ((task.getType() == ShardSyncTaskEntity.MIGRATE_TASK && !dimensionRuleImpl.isMaster()) || (
                task.getType() == ShardSyncTaskEntity.SYNC_TASK && dimensionRuleImpl.isMaster())) {
                continue;
            }

            initDataSources(dimensionRuleImpl.getDataSourceProvider().getAllDBAndTables());
            for (DimensionRule rule : dimensionRuleImpl.getWhiteListRules()) {
                initDataSources(rule.getAllDBAndTables());
            }
        }
    }

    protected void initDataSources(Map<String, Set<String>> all) {
        for (Map.Entry<String, Set<String>> entity : all.entrySet()) {
            String jdbcRef = entity.getKey();
            if (!DataSourceRepository.contains(jdbcRef)) {
                GroupDataSource ds = initGroupDataSource(jdbcRef);
                DataSourceRepository.put(jdbcRef, ds);
            }
        }
    }

    protected GroupDataSource initGroupDataSource(String jdbcRef) {
        GroupDataSource ds = new GroupDataSource(jdbcRef);
        ds.setRouterType(RouterType.FAIL_OVER.getRouterType());
        ds.init();
        return ds;
    }

    protected void initPumaClient() {
        if (task.getType() == ShardSyncTaskEntity.MIGRATE_TASK) {
            initPumaClient(originDsJdbcRef, originDataSource.getConfig(), Sets.newHashSet(task.getTableName()),
                "migrate");
        } else if (task.getType() == ShardSyncTaskEntity.SYNC_TASK) {
            TableShardRule tableShardRule = routerRuleOrigin.getTableShardRules().get(task.getTableName());
            for (DimensionRule dimensionRule : tableShardRule.getDimensionRules()) {
                DimensionRuleImpl dimensionRuleImpl = (DimensionRuleImpl) dimensionRule;
                if (dimensionRuleImpl == null || !dimensionRuleImpl.isMaster()) {
                    continue;
                }

                initPumaClient(dimensionRuleImpl.getDataSourceProvider().getAllDBAndTables(), "master");

                int index = 0;
                for (DimensionRule rule : dimensionRuleImpl.getWhiteListRules()) {
                    initPumaClient(rule.getAllDBAndTables(), "white" + String.valueOf(index++));
                }
            }
        }
    }

    protected void initPumaClient(Map<String, Set<String>> all, String name) {
        for (Map.Entry<String, Set<String>> entity : all.entrySet()) {
            if (pumaClientList.containsKey(entity.getKey())) {
                continue;
            }
            GroupDataSourceConfig config = getGroupDataSourceConfig(entity.getKey());
            initPumaClient(entity.getKey(), config, entity.getValue(), name);
        }
    }

    protected GroupDataSourceConfig getGroupDataSourceConfig(String jdbcRef) {
        return ((GroupDataSource) DataSourceRepository.getDataSource(jdbcRef)).getConfig();
    }

    protected PumaClient initPumaClient(String jdbcRef, GroupDataSourceConfig config, Set<String> tables, String name) {
        DataSourceConfig dsConfig = findTheOnlyWriteDataSourceConfig(config);

        Matcher matcher = JDBC_URL_PATTERN.matcher(dsConfig.getJdbcUrl());
        checkArgument(matcher.matches(), dsConfig.getJdbcUrl());

        String ip = matcher.group(1);
        String ds = matcher.group(2);

        ConfigurationBuilder configBuilder = new ConfigurationBuilder();

        configBuilder.dml(true).ddl(false).transaction(false).port(task.getPumaServerPort())
            .host(task.getPumaServerHost()).target(task.getPumaTaskName());

        String fullName = String.format("%s-%s-%s", task.getPumaTaskName(), ds, name);
        configBuilder.name(fullName);

        for (String tb : tables) {
            configBuilder.tables(ds, tb);
        }

        if (task.getType() == ShardSyncTaskEntity.MIGRATE_TASK) {
            configBuilder.binlog(task.getBinlogName());
            configBuilder.binlogPos(task.getBinlogPos());
        } else if (task.getSeqTimestamp() != null && task.getSeqTimestamp().longValue() > 0) {
            configBuilder.timeStamp(task.getSeqTimestamp().longValue());
        }

        PumaClient client = new PumaClient(configBuilder.build());

        if (task.getType() == ShardSyncTaskEntity.MIGRATE_TASK) {
            client.getSeqFileHolder().saveSeq(SubscribeConstant.SEQ_FROM_BINLOGINFO);
            client.register(new Processor(fullName, Lists.newArrayList(routerForMigrate)));
        } else {
            if (client.getSeqFileHolder().getSeq() == SubscribeConstant.SEQ_FROM_OLDEST) {
                if (task.getSeqTimestamp() != 0) {
                    client.getSeqFileHolder().saveSeq(task.getSeqTimestamp());
                } else {
                    client.getSeqFileHolder().saveSeq(SubscribeConstant.SEQ_FROM_LATEST);
                }
            }
            client.register(new Processor(fullName, routerList));
        }

        pumaClientList.put(jdbcRef, client);
        return client;
    }

    protected DataSourceConfig findTheOnlyWriteDataSourceConfig(GroupDataSourceConfig config) {
        checkNotNull(config, "ds");
        List<DataSourceConfig> dataSourceConfigs = FluentIterable.from(config.getDataSourceConfigs().values())
            .filter(new Predicate<DataSourceConfig>() {
                @Override
                public boolean apply(DataSourceConfig dataSourceConfig) {
                    return dataSourceConfig.isCanWrite();
                }
            }).toList();

        checkArgument(dataSourceConfigs.size() == 1, config.toString());
        return dataSourceConfigs.get(0);
    }

    protected void initAndConvertConfig() {
        try {
            RouterRuleConfig tempRouterRuleConfig = new Gson()
                .fromJson(configCache.getProperty(LionKey.getShardConfigKey(task.getRuleName())),
                    RouterRuleConfig.class);
            this.originDsJdbcRef = configCache.getProperty(LionKey.getShardOriginDatasourceKey(task.getRuleName()));

            if (task.getType() == ShardSyncTaskEntity.MIGRATE_TASK && Strings.isNullOrEmpty(this.originDsJdbcRef)) {
                throw new IllegalArgumentException("no origin ds name!");
            }

            convertOriginRuleConfig(tempRouterRuleConfig);
            convertRuleConfigList();
        } catch (LionException e) {
            throw new RuntimeException(e);
        }

    }

    protected void convertRuleConfigList() {
        for (TableShardDimensionConfig dc : this.tableShardRuleConfigOrigin.getDimensionConfigs()) {
            if (dc.isMaster()) {
                continue;
            }

            TableShardRuleConfig tempConfig = new TableShardRuleConfig();
            tempConfig.setTableName(task.getTableName());

            TableShardDimensionConfig tempDimensionConfig = SerializationUtils.clone(dc);
            tempDimensionConfig.setMaster(true);
            tempConfig.setDimensionConfigs(Lists.newArrayList(tempDimensionConfig));
            tableShardRuleConfigList.add(tempConfig);
        }
    }

    protected void convertOriginRuleConfig(RouterRuleConfig tempRouterRuleConfig) {
        for (TableShardRuleConfig tableConfig : tempRouterRuleConfig.getTableShardConfigs()) {
            if (task.getTableName().equals(tableConfig.getTableName())) {
                this.tableShardRuleConfigOrigin = tableConfig;
                for (TableShardDimensionConfig dimension : this.tableShardRuleConfigOrigin.getDimensionConfigs()) {
                    dimension.setTableName(task.getTableName());
                }
                return;
            }
        }
        checkNotNull(this.tableShardRuleConfigOrigin, "tableShardRuleConfig");
    }

    public void start() {
        if (!inited) {
            return;
        }

        try {
            for (PumaClient client : pumaClientList.values()) {
                client.start();
            }
            //            this.status.setStatus(Status.RUNNING);
        } catch (Exception exp) {
            //            this.status.setStatus(Status.FAILED);
        }
    }

    public void stop() {
        if (!inited) {
            return;
        }

        try {
            for (PumaClient client : pumaClientList.values()) {
                client.stop();
            }
            //            this.status.setStatus(Status.STOPPED);
        } catch (Exception exp) {
            //            this.status.setStatus(Status.FAILED);
        }
    }

    public void setConfigCache(ConfigCache configCache) {
        this.configCache = configCache;
    }

    public class RouterRuleBuilder extends AbstractDataSourceRouterFactory {
        @Override
        public ShardRouter getRouter() {
            throw new IllegalAccessError("");
        }
    }
}
