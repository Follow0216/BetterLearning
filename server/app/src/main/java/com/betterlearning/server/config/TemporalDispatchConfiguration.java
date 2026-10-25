package com.betterlearning.server.config;

import com.betterlearning.server.agent.runtime.RuntimeDeadlineReconciler;
import com.betterlearning.server.agent.runtime.RuntimeDispatchLoop;
import com.betterlearning.server.agent.runtime.TemporalRunGateway;
import com.betterlearning.server.agent.runtime.WorkflowOutboxDispatcher;
import com.betterlearning.server.agent.runtime.WorkflowRuntimeProperties;
import com.betterlearning.server.multiagent.TutorLedger;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.agent.workflow", name = "dispatcher-enabled", havingValue = "true")
public class TemporalDispatchConfiguration {
    @Bean
    RuntimeDispatchLoop runtimeDispatchLoop(TutorLedger ledger, TemporalRunGateway gateway,
            WorkflowRuntimeProperties properties) {
        Clock clock = Clock.systemUTC();
        return new RuntimeDispatchLoop(new WorkflowOutboxDispatcher(ledger, gateway, clock, properties.getDispatchBatchSize()),
                new RuntimeDeadlineReconciler(ledger, clock, properties.getDispatchBatchSize()), properties);
    }
}
