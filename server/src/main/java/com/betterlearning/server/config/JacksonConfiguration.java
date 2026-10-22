package com.betterlearning.server.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;

/**
 * JSON 反序列化的严格模式配置。
 *
 * <p>同时启用 {@code FAIL_ON_UNKNOWN_PROPERTIES} 与 {@code FAIL_ON_IGNORED_PROPERTIES}：
 * 请求体出现未声明的字段，或出现被 {@code @JsonIgnore} 标记的字段时，反序列化直接失败，
 * 不会静默忽略。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfiguration {

    /**
     * 定制 JSON 映射器，使其拒绝未知字段与被忽略字段。
     *
     * @return 反序列化严格模式的定制器
     */
    @Bean
    JsonMapperBuilderCustomizer failOnUnknownJsonProperties() {
        return builder -> builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES);
    }
}
