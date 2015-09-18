/**
 * Project: ${zebra-client.aid}
 *
 * File Created at 2011-6-7 $Id$
 *
 * Copyright 2010 dianping.com. All rights reserved.
 *
 * This software is the confidential and proprietary information of Dianping
 * Company. ("Confidential Information"). You shall not disclose such
 * Confidential Information and shall use it only in accordance with the terms
 * of the license agreement you entered into with dianping.com.
 */
package com.dianping.zebra.shard.jdbc;

import com.dianping.zebra.Constants;
import com.dianping.zebra.config.ConfigService;
import com.dianping.zebra.config.LionConfigService;
import com.dianping.zebra.config.LionKey;
import com.dianping.zebra.config.PropertyConfigService;
import com.dianping.zebra.group.jdbc.AbstractDataSource;
import com.dianping.zebra.group.jdbc.GroupDataSource;
import com.dianping.zebra.shard.router.DataSourceRepository;
import com.dianping.zebra.shard.router.DataSourceRouterFactory;
import com.dianping.zebra.shard.router.LionDataSourceRouterFactory;
import com.dianping.zebra.shard.router.ShardRouter;
import com.dianping.zebra.util.StringUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import javax.sql.DataSource;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * @author Leo Liang
 * @author hao.zhu
 */
public class ShardDataSource extends AbstractDataSource {

	private static final Logger logger = LogManager.getLogger(ShardDataSource.class);

	private String ruleName;

	private Map<String, DataSource> dataSourcePool;

	private DataSourceRouterFactory routerFactory;

	private DataSourceRepository dataSourceRepository;

	private ShardRouter router;

	private volatile boolean switchOn = true;

	private DataSource originDataSource;

	private ConfigService configService;

	private volatile boolean closed = false;

	private final PropertyChangeListener switchOnListener = new PropertyChangeListener() {
		@Override
		public void propertyChange(PropertyChangeEvent evt) {
			if (LionKey.getShardSiwtchOnKey(ruleName).equals(evt.getPropertyName())) {
				switchOn = "true".equals(evt.getNewValue());
			}
		}
	};

	public void close() throws SQLException {
		configService.removePropertyChangeListener(switchOnListener);

		if(dataSourceRepository != null) {
			dataSourceRepository.close();
		}

		if (originDataSource instanceof GroupDataSource) {
			try {
				((GroupDataSource) originDataSource).close();
			} catch (SQLException ignore) {
			}
		}

		closed = true;

		logger.info(String.format("ShardDataSource(%s) successfully closed.", ruleName));
	}

	@Override
	public Connection getConnection() throws SQLException {
		return getConnection(null, null);
	}

	public Connection getConnection(boolean switchOn) throws SQLException {
		return getConnection(null, null, switchOn);
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return getConnection(username, password, this.switchOn);
	}

	public Connection getConnection(String username, String password, boolean switchOn) throws SQLException {
		if (closed) {
			throw new SQLException("Datasource has been closed!");
		}

		if (switchOn) {
			ShardConnection connection = new ShardConnection(username, password);
			connection.setRouter(router);
			connection.setDataSourceRepository(dataSourceRepository);

			return connection;
		} else {
			return originDataSource.getConnection();
		}
	}

	public DataSource getOriginDataSource() {
		return originDataSource;
	}

	public ShardRouter getRouter() {
		return router;
	}

	public void init() {
		if (StringUtils.isNotBlank(ruleName)) {
			if (configService == null) {
				if (Constants.CONFIG_MANAGER_TYPE_REMOTE.equals(configManagerType)) {
					configService = LionConfigService.getInstance();
				} else {
					configService = new PropertyConfigService(ruleName);
				}
			}

			if (routerFactory == null) {
				routerFactory = new LionDataSourceRouterFactory(ruleName);
			}

			final String switchOnValue = configService.getProperty(LionKey.getShardSiwtchOnKey(ruleName));

			this.switchOn = "true".equals(switchOnValue);

			if (originDataSource == null) {
				String originJdbcRef = configService.getProperty(LionKey.getShardOriginDatasourceKey(ruleName));

				if (StringUtils.isNotBlank(originJdbcRef)) {
					GroupDataSource groupDataSource = new GroupDataSource(originJdbcRef);
					groupDataSource.setForceWriteOnLogin(false);
					groupDataSource.init();

					this.originDataSource = groupDataSource;
				}
			}

			configService.addPropertyChangeListener(switchOnListener);
		} else {
			if (dataSourcePool == null || dataSourcePool.isEmpty()) {
				throw new IllegalArgumentException("dataSourcePool is required.");
			}

			if (routerFactory == null) {
				throw new IllegalArgumentException("routerRuleFile must be set.");
			}
		}

		this.router = routerFactory.getRouter();
		this.router.init();

		if (dataSourceRepository == null) {
			dataSourceRepository = DataSourceRepository.getInstance();
		}

		if (dataSourcePool != null) {
			dataSourceRepository.init(dataSourcePool);
		} else {
			dataSourceRepository.init(this.router.getRouterRule());
		}

		logger.info(String.format("ShardDataSource(%s) successfully initialized.", ruleName));
	}

	public void setConfigService(ConfigService configService) {
		this.configService = configService;
	}

	public void setConfigType(String configType) {
		this.configManagerType = configType;
	}

	public void setDataSourcePool(Map<String, DataSource> dataSourcePool) {
		this.dataSourcePool = dataSourcePool;
	}

	public void setOriginDataSource(DataSource originDataSource) {
		this.originDataSource = originDataSource;
	}

	public void setRouterFactory(DataSourceRouterFactory routerFactory) {
		this.routerFactory = routerFactory;
	}

	public void setRuleName(String ruleName) {
		this.ruleName = ruleName;
	}

	public void setSwitchOn(boolean switchOn) {
		this.switchOn = switchOn;
	}

	public void setDataSourceRepository(DataSourceRepository dataSourceRepository) {
		this.dataSourceRepository = dataSourceRepository;
	}
}
