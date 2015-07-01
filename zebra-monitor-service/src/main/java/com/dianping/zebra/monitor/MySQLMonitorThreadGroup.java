package com.dianping.zebra.monitor;

import java.util.Map;

import com.dianping.zebra.group.config.datasource.entity.DataSourceConfig;

public interface MySQLMonitorThreadGroup {

	public void startOrRefreshMonitor(DataSourceConfig dsConfig);

	public void removeMonitor(String dsId);

	public Map<String, MySQLMonitorThread> getMonitors();
}