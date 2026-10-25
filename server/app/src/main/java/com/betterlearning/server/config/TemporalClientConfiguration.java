package com.betterlearning.server.config;

import com.betterlearning.server.agent.runtime.TemporalRunGateway;
import com.betterlearning.server.agent.runtime.WorkflowRuntimeProperties;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Lazy transport initialization lets database deadline reconciliation survive a Temporal outage. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowRuntimeProperties.class)
public class TemporalClientConfiguration {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "app.agent.workflow", name = "enabled", havingValue = "true")
    WorkflowServiceStubs temporalService(WorkflowRuntimeProperties properties) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.getTemporalTarget())
                .setRpcTimeout(Duration.ofSeconds(5))
                .setRpcLongPollTimeout(Duration.ofSeconds(60))
                .build());
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.agent.workflow", name = "enabled", havingValue = "true")
    WorkflowClient temporalClient(WorkflowServiceStubs service, WorkflowRuntimeProperties properties) {
        return WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder()
                .setNamespace(properties.getNamespace()).build());
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.agent.workflow", name = "enabled", havingValue = "true")
    TemporalRunGateway temporalRunGateway(WorkflowClient client, WorkflowRuntimeProperties properties) {
        return new TemporalRunGateway(client, properties);
    }
}
