package com.betterlearning.server.config;

import java.util.ArrayList;
import java.util.List;

import com.betterlearning.server.validation.ValidCorsOrigin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 跨域来源配置。
 *
 * <p>绑定 {@code app.cors} 前缀，启动时要求来源列表非空，且每个来源既不能为空白，也要通过
 * {@link ValidCorsOrigin} 的格式校验。实际来源由 {@code app.cors.allowed-origins} 提供。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Validated
@ConfigurationProperties(prefix = "app.cors")
public class AppCorsProperties {

    /** 允许跨域访问的来源列表。 */
    @NotEmpty(message = "app.cors.allowed-origins 至少需要一个来源")
    private List<@NotBlank(message = "app.cors.allowed-origins 不能包含空白来源")
            @ValidCorsOrigin String> allowedOrigins = new ArrayList<>();

    /** 返回允许跨域访问的来源列表。 */
    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    /** 设置允许跨域访问的来源列表。 */
    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
