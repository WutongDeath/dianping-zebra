package com.dianping.zebra.admin.monitor;

public interface MySQLMonitorServer {
	
	public void addJdbcRef(String jdbcRef);
	
	public void removeJdbcRef(String jdbcRef);
}