package com.dianping.zebra.group.datasources;

import com.dianping.zebra.group.util.DataSourceState;

import javax.sql.DataSource;

public interface MarkableDataSource extends DataSource {

	DataSourceState getState();

	void markClosed();

	void markDown();

	void markUp();
}
