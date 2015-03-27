package com.dianping.zebra.admin.controller;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.dianping.lion.EnvZooKeeperConfig;
import com.dianping.lion.client.ConfigCache;
import com.dianping.lion.client.ConfigChange;
import com.dianping.lion.client.LionException;
import com.dianping.zebra.admin.monitor.MHAService;
import com.dianping.zebra.admin.monitor.MySQLMonitorThreadGroup;
import com.dianping.zebra.admin.service.LionService;

@Controller
@RequestMapping(value = "/mha")
public class MHAController {

	@Autowired
	private MHAService mhaService;

	@Autowired
	private LionService lionService;

	@Autowired
	private MySQLMonitorThreadGroup threadGroup;

	@PostConstruct
	public void init() {
		try {
			ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress()).addChange(new MyConfigChange());
		} catch (LionException e) {
		}
	}

	// 给mha集群调用
	@RequestMapping(value = "/markdown", method = RequestMethod.GET)
	@ResponseBody
	public Object markdown(String ip, String port) {
		MHAResultDto result = new MHAResultDto();

		if (ip != null && port != null) {
			Set<String> dsIds = mhaService.findDsIds(ip, port);

			if (dsIds != null) {
				mhaService.markDownDsIds(dsIds);
			}

			result.setDsIds(dsIds);
		}

		result.setStatus("success");

		return result;
	}

	// 给zebra-web界面调用
	@RequestMapping(value = "/markup", method = RequestMethod.GET)
	@ResponseBody
	public Object markup(String dsId) {
		MHAResultDto result = new MHAResultDto();

		if (dsId != null) {
			mhaService.markUpDsId(dsId);

			result.addDsId(dsId);
		}

		result.setStatus("success");

		return result;
	}

	public class MHAResultDto {
		private String status;

		private Set<String> dsIds;

		public void addDsId(String dsId) {
			if (dsIds == null) {
				dsIds = new HashSet<String>();
			}

			dsIds.add(dsId);
		}

		public Set<String> getDsIds() {
			return dsIds;
		}

		public String getStatus() {
			return status;
		}

		public void setDsIds(Set<String> dsIds) {
			this.dsIds = dsIds;
		}

		public void setStatus(String status) {
			this.status = status;
		}
	}

	private class MyConfigChange implements ConfigChange {

		@Override
		public void onChange(String key, String value) {
			if (key.equalsIgnoreCase("zebra.server.monitor.mha.markdown")) {
				String config = lionService.getConfigFromZk("zebra.server.monitor.mha.markdown");

				if (config != null) {
					String[] dsIds = config.split(",");
					Map<String, String> mhaMarkedDownDs = new ConcurrentHashMap<String, String>();

					for (String dsId : dsIds) {
						if (dsId != null && dsId.length() > 0) {
							mhaMarkedDownDs.put(dsId, dsId);
						}
					}

					for (String dsId : mhaMarkedDownDs.keySet()) {
						threadGroup.suspendMonitor(dsId, true);
					}
				}
			}
		}
	}
}
