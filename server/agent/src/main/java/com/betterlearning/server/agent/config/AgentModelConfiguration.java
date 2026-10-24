package com.betterlearning.server.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 仅注册配置，调用前才检查真实接入值；未配置AI不阻断M1/M2启动。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
public class AgentModelConfiguration {
}
