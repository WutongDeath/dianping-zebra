/**
 * Project: com.dianping.zebra.zebra-client-0.1.0
 *
 * File Created at 2011-6-14
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
package com.dianping.zebra.shard.router.rule;

import com.dianping.zebra.shard.config.TableShardDimensionConfig;
import com.dianping.zebra.shard.router.rule.engine.GroovyRuleEngine;
import com.dianping.zebra.shard.router.rule.engine.RuleEngine;
import com.dianping.zebra.shard.router.rule.engine.RuleEngineEvalContext;

import java.util.*;
import java.util.Map.Entry;

/**
 * @author danson.liu
 */
public class DimensionRuleImpl extends AbstractDimensionRule {

	private String tableName;

	private RuleEngine ruleEngine;

	private List<DimensionRule> whiteListRules;

	private DataSourceProvider dataSourceProvider;

	private Map<String, Set<String>> allDBAndTables = new HashMap<String, Set<String>>();

	public Map<String, Set<String>> getAllDBAndTables() {
		return allDBAndTables;
	}

	public DataSourceProvider getDataSourceProvider() {
		return this.dataSourceProvider;
	}

	public RuleEngine getRuleEngine() {
		return ruleEngine;
	}

	public List<DimensionRule> getWhiteListRules() {
		return whiteListRules;
	}

	public void init(TableShardDimensionConfig dimensionConfig) {
		this.isMaster = dimensionConfig.isMaster();
		this.tableName = dimensionConfig.getTableName();
		this.dataSourceProvider = new SimpleDataSourceProvider(dimensionConfig.getTableName(),
		      dimensionConfig.getDbIndexes(), dimensionConfig.getTbSuffix(), dimensionConfig.getTbRule());
		allDBAndTables.putAll(this.dataSourceProvider.getAllDBAndTables());
		for (DimensionRule whiteListRule : this.whiteListRules) {
			Map<String, Set<String>> whiteListDBAndTables = whiteListRule.getAllDBAndTables();
			for (Entry<String, Set<String>> allDBAndTable : whiteListDBAndTables.entrySet()) {
				String db = allDBAndTable.getKey();
				if (!allDBAndTables.containsKey(db)) {
					allDBAndTables.put(db, new HashSet<String>());
				}
				allDBAndTables.get(db).addAll(allDBAndTable.getValue());
			}
		}
		this.ruleEngine = new GroovyRuleEngine(dimensionConfig.getDbRule());
		this.initShardColumn(dimensionConfig.getDbRule());
	}

	@Override
	public boolean match(ShardMatchContext matchContext) {
		ShardMatchResult matchResult = matchContext.getMatchResult();
		boolean onlyMatchMaster = matchContext.onlyMatchMaster();
		boolean onlyMatchOnce = matchContext.onlyMatchOnce();
		Set<Object> shardColValues = ShardColumnValueUtil.eval(matchContext.getDmlSql(), tableName, shardColumn,
		      matchContext.getParams());
		if (shardColValues == null || shardColValues.isEmpty()) {
			if (onlyMatchMaster && isMaster) {
				// 强制匹配了Master Rule，将该主规则所有表设置为主路由结果
				matchResult.setDbAndTables(allDBAndTables);
				matchResult.setDbAndTablesSetted(true);
				return !onlyMatchOnce;
			} else {
				if (!matchResult.isPotentialDBAndTbsSetted()) {
					matchResult.setPotentialDBAndTbs(allDBAndTables);
					matchResult.setPotentialDBAndTbsSetted(true);
				}

				return true;
			}
		}

		boolean dbAndTablesSetted = matchResult.isDbAndTablesSetted();
		matchContext.setColValues(shardColValues);
		for (DimensionRule whiteListRule : whiteListRules) {
			whiteListRule.match(matchContext);
		}
		for (Object colVal : matchContext.getColValues()) {
			Map<String, Object> valMap = new HashMap<String, Object>();
			valMap.put(shardColumn, colVal);

			RuleEngineEvalContext context = new RuleEngineEvalContext(valMap);
			Number dbPos = (Number) ruleEngine.eval(context);
			DataSourceBO dataSource = dataSourceProvider.getDataSource(dbPos.intValue());
			String table = dataSource.evalTable(context);
			if (!dbAndTablesSetted) {
				matchResult.addDBAndTable(dataSource.getDbIndex(), table);
			}
		}
		if (!dbAndTablesSetted) {
			matchResult.setDbAndTablesSetted(true);
		}
		return !onlyMatchOnce;
	}

	public void setRuleEngine(RuleEngine ruleEngine) {
		this.ruleEngine = ruleEngine;
	}

	public void setWhiteListRules(List<DimensionRule> whiteListRules) {
		this.whiteListRules = whiteListRules;
	}
}
