package com.betterlearning.server.config;

import com.betterlearning.server.learning.service.RolloutGate;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** HTTP 开始服务前核对数据库模式；显式声明来源模式时才申请一次性 CAS 切换。 */
@Configuration(proxyBeanMethods = false)
public class MultiAgentRolloutConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    @DependsOn("sourceDeletionService")
    SmartInitializingSingleton multiAgentRolloutCheck(RolloutGate gate,
            @Value("${app.multi-agent.rollout.mode}") String mode,
            @Value("${app.multi-agent.rollout.transition-from:}") String from) {
        return () -> {
            if (from.isBlank()) {
                gate.assertMode(mode);
            } else {
                gate.switchFrom(from, mode);
            }
        };
    }
}
