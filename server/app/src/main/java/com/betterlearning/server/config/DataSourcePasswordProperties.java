package com.betterlearning.server.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 数据源口令校验配置。
 *
 * <p>绑定 {@code spring.datasource} 的 {@code password}，启动时要求非空，错误信息指向
 * 环境变量 {@code SPRING_DATASOURCE_PASSWORD}。该值没有内置默认值，也不得写入日志或
 * {@code toString}。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Validated
@ConfigurationProperties(prefix = "spring.datasource")
public class DataSourcePasswordProperties {

    /** 数据库口令，无默认值。 */
    @NotBlank(message = "SPRING_DATASOURCE_PASSWORD 不能为空")
    private String password;

    /** 返回数据库口令。 */
    public String getPassword() {
        return password;
    }

    /** 设置数据库口令。 */
    public void setPassword(String password) {
        this.password = password;
    }
}
