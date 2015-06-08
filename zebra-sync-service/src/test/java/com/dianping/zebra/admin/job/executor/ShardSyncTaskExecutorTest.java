package com.dianping.zebra.admin.job.executor;

import com.dianping.lion.client.ConfigCache;
import com.dianping.lion.client.LionException;
import com.dianping.puma.api.Configuration;
import com.dianping.puma.api.PumaClient;
import com.dianping.puma.core.event.ChangedEvent;
import com.dianping.puma.core.event.RowChangedEvent;
import com.dianping.zebra.admin.entity.ShardSyncTaskEntity;
import com.dianping.zebra.group.config.datasource.entity.DataSourceConfig;
import com.dianping.zebra.group.config.datasource.entity.GroupDataSourceConfig;
import com.dianping.zebra.group.jdbc.GroupDataSource;
import com.dianping.zebra.shard.config.RouterRuleConfig;
import com.dianping.zebra.shard.config.TableShardDimensionConfig;
import com.dianping.zebra.shard.config.TableShardRuleConfig;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import junit.framework.Assert;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.Set;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anySet;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

/**
 * Dozer @ 6/8/15
 * mail@dozer.cc
 * http://www.dozer.cc
 */
public class ShardSyncTaskExecutorTest {
    ShardSyncTaskEntity task = new ShardSyncTaskEntity();

    ShardSyncTaskExecutor target;

    ConfigCache configCache = mock(ConfigCache.class);

    @Before
    public void init() {
        this.task.setRuleName("test");
        this.task.setTableName("table1");
        this.task.setBinlogName("mysql-bin.000001");
        this.target = new ShardSyncTaskExecutor(task);
        this.target.setConfigCache(configCache);
    }

    @Test
    public void initPumaClientTest() throws IllegalAccessException, NoSuchMethodException {
        //prepare
        GroupDataSourceConfig config = new GroupDataSourceConfig();
        DataSourceConfig ds1 = new DataSourceConfig();
        ds1.setCanWrite(true);
        ds1.setJdbcUrl("jdbc:mysql://1.1.1.1:3306/db1?a=b");
        config.getDataSourceConfigs().put("db1", ds1);

        Set<String> tables = Sets.newHashSet("t1", "t2");
        target.task.setPumaServerHost("10.0.0.1");
        target.task.setPumaServerPort(8080);
        target.task.setPumaTaskName("task");
        //run
        PumaClient actual = target.initPumaClient("debug", config, tables, "debug");

        //verify
        Configuration clientConfig = (Configuration) FieldUtils.readField(actual, "config", true);
        Assert.assertEquals(true, clientConfig.isNeedDml());
        Assert.assertEquals(false, clientConfig.isNeedDdl());
        Assert.assertEquals(false, clientConfig.isNeedTransactionInfo());
        Assert.assertTrue(clientConfig.getDatabaseTablesMapping().containsKey("db1"));
        Assert.assertEquals(tables.toString(), clientConfig.getDatabaseTablesMapping().get("db1").toString());

        Assert.assertEquals("task-db1-debug", clientConfig.getName());
        System.out.println("PumaClient :" + clientConfig.getName());
    }

    @Test
    public void findTheOnlyWriteDataSourceConfigTestSuccess() {
        GroupDataSourceConfig config = new GroupDataSourceConfig();
        DataSourceConfig ds1 = new DataSourceConfig();
        ds1.setCanWrite(true);
        DataSourceConfig ds2 = new DataSourceConfig();
        ds2.setCanWrite(false);
        config.getDataSourceConfigs().put("1", ds1);
        config.getDataSourceConfigs().put("2", ds2);

        Assert.assertEquals(ds1, target.findTheOnlyWriteDataSourceConfig(config));
    }

    @Test(expected = Exception.class)
    public void findTheOnlyWriteDataSourceConfigTestFailed() {
        GroupDataSourceConfig config = new GroupDataSourceConfig();
        DataSourceConfig ds1 = new DataSourceConfig();
        ds1.setCanWrite(true);
        DataSourceConfig ds2 = new DataSourceConfig();
        ds2.setCanWrite(true);
        config.getDataSourceConfigs().put("1", ds1);
        config.getDataSourceConfigs().put("2", ds2);

        target.findTheOnlyWriteDataSourceConfig(config);
    }

    @Test
    public void initSyncDataSourceTest() {
        ShardSyncTaskExecutor spy = initDataSourceTest(false);
        verify(spy, times(2)).initGroupDataSource(anyString());
        verify(spy, times(1)).initGroupDataSource("ds4");
        verify(spy, times(1)).initGroupDataSource("ds5");
    }

    @Test
    public void initMigrateDataSourceTest() {
        ShardSyncTaskExecutor spy = initDataSourceTest(true);
        verify(spy, times(6)).initGroupDataSource(anyString());
        verify(spy, times(1)).initGroupDataSource("origin");
        verify(spy, times(1)).initGroupDataSource("ds0");
        verify(spy, times(1)).initGroupDataSource("ds1");
        verify(spy, times(1)).initGroupDataSource("ds2");
        verify(spy, times(1)).initGroupDataSource("ds3");
        verify(spy, times(1)).initGroupDataSource("ds8");
    }

    private ShardSyncTaskExecutor initDataSourceTest(boolean isMigrate) {
        task.setIsMigrate(isMigrate);
        ShardSyncTaskExecutor spy = spy(target);

        doAnswer(new Answer<GroupDataSource>() {
            @Override
            public GroupDataSource answer(InvocationOnMock invocationOnMock) throws Throwable {
                System.out.println("init ds:" + invocationOnMock.getArguments()[0].toString());
                GroupDataSource ds = new GroupDataSource(invocationOnMock.getArguments()[0].toString());
                return ds;
            }
        }).when(spy).initGroupDataSource(anyString());

        TableShardRuleConfig tableShardRuleConfig = buildTableConfigFromFile("initPumaClientsAndDataSourcesTest.json");
        spy.tableShardRuleConfigOrigin = tableShardRuleConfig;
        spy.originDsJdbcRef = "origin";
        spy.initRouterRule();

        spy.initDataSources();
        return spy;
    }

    @Test
    public void initMigratePumaClientsTest() {
        ShardSyncTaskExecutor spy = initPumaClientsTest(true);
        verify(spy, times(1)).initPumaClient(anyString(), any(GroupDataSourceConfig.class), anySet(), anyString());
        verify(spy, times(1))
            .initPumaClient(anyString(), any(GroupDataSourceConfig.class), argThat(new SetMatchers("table1")),
                eq("migrate"));
    }

    @Test
    public void initSyncPumaClientsTest() {
        ShardSyncTaskExecutor spy = initPumaClientsTest(false);

        verify(spy, times(6)).initPumaClient(anyString(), any(GroupDataSourceConfig.class), anySet(), anyString());
        verify(spy, times(1)).initPumaClient(anyString(), any(GroupDataSourceConfig.class),
            argThat(new SetMatchers("table1_0", "table1_1")), eq("master"));
        verify(spy, times(1)).initPumaClient(anyString(), any(GroupDataSourceConfig.class),
            argThat(new SetMatchers("table1_2", "table1_3")), eq("master"));
        verify(spy, times(1)).initPumaClient(anyString(), any(GroupDataSourceConfig.class),
            argThat(new SetMatchers("table1_4", "table1_5")), eq("master"));
        verify(spy, times(1)).initPumaClient(anyString(), any(GroupDataSourceConfig.class),
            argThat(new SetMatchers("table1_6", "table1_7")), eq("master"));
        verify(spy, times(1))
            .initPumaClient(anyString(), any(GroupDataSourceConfig.class), argThat(new SetMatchers("ds1_white")),
                anyString());
        verify(spy, times(1))
            .initPumaClient(anyString(), any(GroupDataSourceConfig.class), argThat(new SetMatchers("ds8_white")),
                anyString());
    }

    private ShardSyncTaskExecutor initPumaClientsTest(boolean isMigrate) {
        task.setIsMigrate(isMigrate);
        ShardSyncTaskExecutor spy = spy(target);

        doReturn(null).when(spy).getGroupDataSourceConfig(anyString());

        doAnswer(new Answer<PumaClient>() {
            @Override
            public PumaClient answer(InvocationOnMock invocationOnMock) throws Throwable {
                System.out.println("init pumaclient:" + invocationOnMock.getArguments()[1]);
                System.out.println("init pumaclient:" + invocationOnMock.getArguments()[2]);
                System.out.println("init pumaclient:" + invocationOnMock.getArguments()[3]);
                return null;
            }
        }).when(spy).initPumaClient(anyString(), any(GroupDataSourceConfig.class), anySet(), anyString());

        TableShardRuleConfig tableShardRuleConfig = buildTableConfigFromFile("initPumaClientsAndDataSourcesTest.json");
        spy.tableShardRuleConfigOrigin = tableShardRuleConfig;
        spy.tableShardRuleConfigList = Lists.newArrayList(tableShardRuleConfig);
        spy.initRouterRule();
        spy.originDsJdbcRef = "origin";
        spy.originDataSource = new GroupDataSource();

        spy.initPumaClient();
        return spy;
    }

    class SetMatchers extends ArgumentMatcher<Set<String>> {
        private final String[] expects;

        public SetMatchers(String... expects) {
            this.expects = expects;
        }

        @Override
        public boolean matches(Object argument) {
            Set<String> args = (Set<String>) argument;

            if (expects.length != args.size()) {
                return false;
            }

            for (String expect : expects) {
                if (!args.contains(expect)) {
                    return false;
                }
            }

            return true;
        }
    }

    @Test
    public void initAndConvertConfigTest() throws LionException {
        RouterRuleConfig config = new RouterRuleConfig();
        TableShardRuleConfig tConfig = buildTableConfigFromFile("initPumaClientsAndDataSourcesTest.json");
        config.setTableShardConfigs(Lists.newArrayList(tConfig));

        when(configCache.getProperty("shardds.test.shard")).thenReturn(new Gson().toJson(config));
        when(configCache.getProperty("shardds.test.origin")).thenReturn("table");

        target.initAndConvertConfig();

        verify(configCache, times(1)).getProperty("shardds.test.shard");
        verify(configCache, times(1)).getProperty("shardds.test.origin");
        verify(configCache, times(2)).getProperty(anyString());

        Assert.assertEquals("table1", target.tableShardRuleConfigOrigin.getTableName());
        Assert.assertEquals(1, target.tableShardRuleConfigList.size());
        Assert.assertEquals(1, target.tableShardRuleConfigList.get(0).getDimensionConfigs().size());
        Assert.assertEquals("table", target.originDsJdbcRef);
    }

    @Test
    public void processOnExceptionTest() throws Exception {
        ShardSyncTaskExecutor.Processor processor = spy(target.new Processor("test", null));
        Assert.assertEquals(false, processor.onException(new RowChangedEvent(), new SQLException("error")));

        processor.tryTimes = processor.MAX_TRY_TIMES + 1;
        Assert.assertEquals(true, processor.onException(new RowChangedEvent(), new SQLException("error")));

        verify(processor, times(2)).logException(any(ChangedEvent.class), any(Exception.class));
    }

    @Test
    public void initRouterConfigTest() {
        TableShardRuleConfig config = new TableShardRuleConfig();
        config.setTableName("test1");

        TableShardDimensionConfig dimensionConfig = new TableShardDimensionConfig();
        dimensionConfig.setMaster(true);
        dimensionConfig.setTableName("test1");
        dimensionConfig.setDbIndexes("db1");
        dimensionConfig.setDbRule("(#id# % 4 / 4)");
        dimensionConfig.setTbRule("(#id# % 4)");
        dimensionConfig.setTbSuffix("alldb:[_0,_3]");

        config.setDimensionConfigs(Lists.newArrayList(dimensionConfig));

        target.tableShardRuleConfigOrigin = config;
        target.tableShardRuleConfigList = Lists.newArrayList(config);
        target.initRouterRule();

        Assert.assertEquals(1, target.routerRuleOrigin.getTableShardRules().size());
    }

    private TableShardRuleConfig buildTableConfigFromFile(String file) {
        return new Gson().fromJson(
            new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream("shard-configs/" + file)),
            TableShardRuleConfig.class);
    }
}