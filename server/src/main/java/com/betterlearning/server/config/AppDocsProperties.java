package com.betterlearning.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 接口文档开关配置。
 *
 * <p>绑定 {@code app.docs} 前缀。{@code enabled} 默认为 {@code false}，取值为 {@code true} 时
 * 才启用 Swagger UI 与 {@code /v3/api-docs}；该开关同时约束两者，不单独暴露其中之一。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Validated
@ConfigurationProperties(prefix = "app.docs")
public class AppDocsProperties {

    /** 是否启用接口文档，默认关闭。 */
    private boolean enabled = false;

    /** 返回是否启用接口文档。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 设置是否启用接口文档。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
