package com.dianping.zebra.group.router;/** * 对等DataSource选择器。 *  */public interface DataSourceRouter {	/**	 * 路由策略名称	 */	String getName();	/**	 * 对等DataSource选择器。 在数据完全相同的一组DataSource中选择一个DataSource	 */	RounterTarget select(RouterContext routerContext);}