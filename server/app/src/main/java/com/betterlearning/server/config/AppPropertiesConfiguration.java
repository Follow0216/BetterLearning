package com.betterlearning.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 配置属性注册入口。
 *
 * <p>集中启用应用自定义的配置属性绑定，使各属性类在启动时完成绑定与校验。使用
 * {@code proxyBeanMethods = false} 避免为配置类创建 CGLIB 代理。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        AppJwtProperties.class,
        AppAuthProperties.class,
        AppCorsProperties.class,
        DataSourcePasswordProperties.class
})
public class AppPropertiesConfiguration {
}
