package com.betterlearning.server.agent.batch;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 只注册批量 OCR 独立 Mapper。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.betterlearning.server.agent.batch.mapper")
public class BatchOcrConfiguration {
}
