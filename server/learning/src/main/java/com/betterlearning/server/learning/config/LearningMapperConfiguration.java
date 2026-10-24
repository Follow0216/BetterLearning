package com.betterlearning.server.learning.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 仅增加 learning Mapper 扫描，沿用应用已有 MyBatis 工厂及固定安全配置。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.betterlearning.server.learning.mapper")
public class LearningMapperConfiguration {
}
