/*
 * Copyright (C) 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.alibaba.nacos.refresh;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.alibaba.nacos.NacosPropertySourceRepository;
import org.springframework.cloud.alibaba.nacos.client.NacosPropertySource;
import org.springframework.cloud.endpoint.event.RefreshEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.util.StringUtils;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;

/**
 * On application start up, NacosContextRefresher add nacos listeners to all application
 * level dataIds, when there is a change in the data, listeners will refresh
 * configurations.
 *
 * @author juven.xuxb
 * @author pbting
 */
public class NacosContextRefresher
		implements ApplicationListener<ApplicationReadyEvent>, ApplicationContextAware {

	private final static Logger LOGGER = LoggerFactory
			.getLogger(NacosContextRefresher.class);

	private final NacosRefreshProperties refreshProperties;

	private final NacosRefreshHistory refreshHistory;

	private final NacosPropertySourceRepository nacosPropertySourceRepository;

	private final ConfigService configService;

	private ApplicationContext applicationContext;

	private AtomicBoolean ready = new AtomicBoolean(true);

	private Map<String, Listener> listenerMap = new ConcurrentHashMap<>(16);

	public NacosContextRefresher(NacosRefreshProperties refreshProperties,
			NacosRefreshHistory refreshHistory,
			NacosPropertySourceRepository nacosPropertySourceRepository,
			ConfigService configService) {
		this.refreshProperties = refreshProperties;
		this.refreshHistory = refreshHistory;
		this.nacosPropertySourceRepository = nacosPropertySourceRepository;
		this.configService = configService;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		// many Spring context
		if (this.ready.get()) {
			this.registerNacosListenersForApplications();
			this.ready.compareAndSet(true, false);
		}
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	private void registerNacosListenersForApplications() {
		if (refreshProperties.isEnabled()) {
			for (NacosPropertySource nacosPropertySource : nacosPropertySourceRepository
					.getAll()) {
				if (!nacosPropertySource.isRefreshable()) {
					continue;
				}

				String dataId = nacosPropertySource.getDataId();
				registerNacosListener(nacosPropertySource.getGroup(), dataId);
			}
		}
	}

	private void registerNacosListener(final String group, final String dataId) {

		Listener listener = listenerMap.computeIfAbsent(dataId, i -> new Listener() {
			@Override
			public void receiveConfigInfo(String configInfo) {
				String md5 = "";
				if (!StringUtils.isEmpty(configInfo)) {
					try {
						MessageDigest md = MessageDigest.getInstance("MD5");
						md5 = new BigInteger(1, md.digest(configInfo.getBytes("UTF-8")))
								.toString(16);
					}
					catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
						LOGGER.warn("[Nacos] unable to get md5 for dataId: " + dataId, e);
					}
				}
				refreshHistory.add(dataId, md5);
				applicationContext.publishEvent(
						new RefreshEvent(this, null, "Refresh Nacos config"));
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Refresh Nacos config group{},dataId{}", group, dataId);
				}
			}

			@Override
			public Executor getExecutor() {
				return null;
			}
		});

		try {
			configService.addListener(dataId, group, listener);
		}
		catch (NacosException e) {
			e.printStackTrace();
		}
	}

}
