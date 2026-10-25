package com.betterlearning.server.config;

import com.betterlearning.server.learning.service.RolloutGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 启动时核对数据库模式；显式声明来源模式时才申请一次性 CAS 切换。 */
@Configuration(proxyBeanMethods = false)
public class MultiAgentRolloutConfiguration {
    @Bean
    ApplicationRunner multiAgentRolloutRunner(RolloutGate gate,
            @Value("${app.multi-agent.rollout.mode}") String mode,
            @Value("${app.multi-agent.rollout.transition-from:}") String from) {
        return arguments -> {
            if (from.isBlank()) {
                gate.assertMode(mode);
            } else {
                gate.switchFrom(from, mode);
            }
        };
    }
}
