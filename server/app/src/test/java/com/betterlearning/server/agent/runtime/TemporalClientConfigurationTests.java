package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.config.TemporalClientConfiguration;
import com.betterlearning.server.config.TemporalWorkerConfiguration;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalClientConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TemporalClientConfiguration.class, TemporalWorkerConfiguration.class);

    @Test
    void disabledRuntimeDoesNotCreateTemporalConnectionOrWorker() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(WorkflowClient.class)
                    .doesNotHaveBean(TemporalWorkerHost.class);
        });
    }

    @Test
    void environmentMappingIsIndependentAndClientCanStartDuringTemporalOutage() {
        runner.withPropertyValues("APP_AGENT_WORKFLOW_ENABLED=true",
                "APP_AGENT_WORKFLOW_TEMPORAL_TARGET=127.0.0.1:1",
                "APP_AGENT_WORKFLOW_NAMESPACE=p2-config-test",
                "APP_AGENT_WORKFLOW_TASK_QUEUE=p2-config-queue",
                "APP_AGENT_WORKFLOW_MODEL_TIMEOUT=PT45S",
                "APP_AGENT_WORKFLOW_MAX_CONCURRENT_ACTIVITIES=2").run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(WorkflowClient.class)
                            .doesNotHaveBean(TemporalWorkerHost.class);
                    WorkflowRuntimeProperties properties = context.getBean(WorkflowRuntimeProperties.class);
                    assertThat(properties.getTemporalTarget()).isEqualTo("127.0.0.1:1");
                    assertThat(properties.getNamespace()).isEqualTo("p2-config-test");
                    assertThat(properties.getTaskQueue()).isEqualTo("p2-config-queue");
                    assertThat(properties.getModelTimeout().toSeconds()).isEqualTo(45);
                    assertThat(properties.getMaxConcurrentActivities()).isEqualTo(2);
                });
    }

    @Test
    void enabledWorkerRequiresActualActivityAdapters() {
        runner.withPropertyValues("APP_AGENT_WORKFLOW_ENABLED=true",
                "APP_AGENT_WORKFLOW_WORKER_ENABLED=true").run(context -> assertThat(context).hasFailed());
    }
}
