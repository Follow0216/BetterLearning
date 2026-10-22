package com.betterlearning.server.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusPropertiesCustomizer;

import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 固定装配约定。
 *
 * <p>只扫描 {@code mapper} 包下的 Mapper；SqlSessionFactory、SqlSessionTemplate 与事务管理器
 * 由 starter 自动构建，不在此手工创建。缓存、执行器、映射与日志策略集中通过属性定制器固定。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(MybatisConfiguration.MAPPER_PACKAGE)
public class MybatisConfiguration {

    /** Mapper 接口所在包。 */
    static final String MAPPER_PACKAGE = "com.betterlearning.server.mapper";

    /** Mapper XML 固定位置。 */
    static final String MAPPER_LOCATIONS = "classpath*:/mapper/**/*.xml";

    /**
     * 固定 MyBatis-Plus 运行策略。
     *
     * <p>缓存关闭、本地缓存仅语句级、执行器固定 SIMPLE、开启驼峰映射，并使用不输出 SQL 的日志实现，
     * 避免参数值进入日志。顶层执行器同时设置，确保自动构建的 SqlSessionTemplate 也使用 SIMPLE。</p>
     *
     * @return 属性定制器
     */
    @Bean
    MybatisPlusPropertiesCustomizer mybatisPlusPropertiesCustomizer() {
        return properties -> {
            properties.setMapperLocations(new String[] {MAPPER_LOCATIONS});
            properties.setExecutorType(ExecutorType.SIMPLE);
            MybatisPlusProperties.CoreConfiguration configuration = properties.getConfiguration();
            if (configuration == null) {
                configuration = new MybatisPlusProperties.CoreConfiguration();
                properties.setConfiguration(configuration);
            }
            configuration.setCacheEnabled(false);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.setDefaultExecutorType(ExecutorType.SIMPLE);
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLogImpl(NoLoggingImpl.class);
        };
    }
}
