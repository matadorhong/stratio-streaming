/**
 * Copyright (C) 2014 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stratio.streaming.shell.provider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.shell.plugin.support.DefaultBannerProvider;
import org.springframework.shell.support.util.FileUtils;
import org.springframework.shell.support.util.OsUtils;
import org.springframework.stereotype.Component;

import com.stratio.streaming.api.IStratioStreamingAPI;
import com.stratio.streaming.shell.Main;

/**
 * @author Jarred Li
 * 
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StratioStreamingBannerProvider extends DefaultBannerProvider {

    @Value("${kafka.host}")
    private String kafkaHost;

    @Value("${kafka.port}")
    private String kafkaPort;

    @Value("${zookeeper.host}")
    private String zookeeperHost;

    @Value("${zookeeper.port}")
    private String zookeeperPort;

    private String version = "0.3.4";

    @Autowired
    private IStratioStreamingAPI stratioStreamingAPI;

    @Override
    public String getBanner() {
        StringBuilder sb = new StringBuilder();
        sb.append(FileUtils.readBanner(Main.class, "/banner.txt"));
        sb.append(OsUtils.LINE_SEPARATOR);
        sb.append("Version:" + this.getVersion() + OsUtils.LINE_SEPARATOR);
        sb.append(OsUtils.LINE_SEPARATOR);
        sb.append("Connection urls: " + OsUtils.LINE_SEPARATOR);
        sb.append("    - Kafka: " + kafkaHost + ":" + kafkaPort + OsUtils.LINE_SEPARATOR);
        sb.append("    - Zookeeper: " + zookeeperHost + ":" + zookeeperPort + OsUtils.LINE_SEPARATOR);

        return sb.toString();
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getWelcomeMessage() {
        return "Welcome to Stratio Streaming Shell";
    }

    @Override
    public String getProviderName() {
        return "Stratio Streaming";
    }

}