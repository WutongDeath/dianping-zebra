/**
 * Project: zebra-client
 * 
 * File Created at 2011-6-27
 * $Id$
 * 
 * Copyright 2010 dianping.com.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Dianping Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with dianping.com.
 */
package com.dianping.zebra.shard.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.Assert;
import org.junit.Test;

/**
 * 
 * @author Leo Liang
 * 
 */
public class DataSourceTest extends ZebraBaseTestCase {
	@Test
	public void testInitWithoutDataSourcePool() {
		ShardDataSource dataSource = new ShardDataSource();
		try {
			dataSource.init();
			Assert.fail("DPDataSource can't init without dataSourcePool");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals("dataSourcePool is required.", e.getMessage());
		}
	}

	@Test
	public void testInitWithoutDataSourcePool2() {
		ShardDataSource dataSource = new ShardDataSource();
		try {
			dataSource.setDataSourcePool(new HashMap<String, DataSource>());
			dataSource.init();
			Assert.fail("DPDataSource can't init with an empty dataSourcePool");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals("dataSourcePool is required.", e.getMessage());
		}
	}

	@Test
	public void testInitWithoutRouterFactory() {
		ShardDataSource dataSource = new ShardDataSource();
		try {
			Map<String, DataSource> dataSourcePool = new HashMap<String, DataSource>();
			dataSourcePool.put("mock", new MockDataSource("mock"));
			dataSource.setDataSourcePool(dataSourcePool);
			dataSource.init();
			Assert.fail("DPDataSource can't init without routerFactory");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals("routerRuleFile must be set.", e.getMessage());
		}
	}

	@Test
	public void testGetConnection() throws SQLException {
		ShardDataSource dataSource = new ShardDataSource();
		Connection conn = dataSource.getConnection();
		Assert.assertNotNull(conn);
		Assert.assertTrue((conn instanceof ShardConnection));
	}

	@Test
	public void testGetConnection2() throws SQLException {
		ShardDataSource dataSource = new ShardDataSource();
		Connection conn = dataSource.getConnection("leo", "test");
		Assert.assertNotNull(conn);
		Assert.assertTrue((conn instanceof ShardConnection));
	}

	protected String[] getSupportedOps() {
		return new String[] { "getConnection", "init", "setDataSourcePool", "setSyncEventNotifier", "getEventNotifier",
				"setRouterFactory", "isPerformanceMonitorSwitchOn", "setPerformanceMonitorSwitchOn" };
	}

	protected Object getTestObj() {
		return new ShardDataSource();
	}

}
