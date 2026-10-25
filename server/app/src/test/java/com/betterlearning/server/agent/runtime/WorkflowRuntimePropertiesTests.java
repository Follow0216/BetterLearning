package com.betterlearning.server.agent.runtime;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRuntimePropertiesTests {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void safeDefaultsAreValid() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isWorkerEnabled()).isFalse();
        assertThat(properties.isDispatcherEnabled()).isFalse();
        assertThat(properties.getTemporalTarget()).isEqualTo("127.0.0.1:7233");
        assertThat(properties.getNamespace()).isEqualTo("default");
        assertThat(properties.getTaskQueue()).isEqualTo("betterlearning-tutor-v1");
        assertThat(properties.getModelTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.getStorageTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getDispatchInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.getReconciliationInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getMaxConcurrentActivities()).isEqualTo(4);
        assertThat(properties.getDispatchBatchSize()).isEqualTo(50);
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void bindsInclusiveBoundaries() {
        runner.withPropertyValues("app.agent.workflow.enabled=true",
                "app.agent.workflow.worker-enabled=true",
                "app.agent.workflow.dispatcher-enabled=true",
                "app.agent.workflow.temporal-target=temporal.internal:7233",
                "app.agent.workflow.namespace=learning",
                "app.agent.workflow.task-queue=tutor-runtime",
                "app.agent.workflow.model-timeout=2m",
                "app.agent.workflow.storage-timeout=1ms",
                "app.agent.workflow.dispatch-interval=1m",
                "app.agent.workflow.reconciliation-interval=5m",
                "app.agent.workflow.shutdown-timeout=1ms",
                "app.agent.workflow.max-concurrent-activities=64",
                "app.agent.workflow.dispatch-batch-size=500").run(context -> {
                    assertThat(context).hasNotFailed();
                    WorkflowRuntimeProperties properties = context.getBean(WorkflowRuntimeProperties.class);
                    assertThat(properties.isWorkerEnabled()).isTrue();
                    assertThat(properties.isDispatcherEnabled()).isTrue();
                    assertThat(properties.getTemporalTarget()).isEqualTo("temporal.internal:7233");
                    assertThat(properties.getNamespace()).isEqualTo("learning");
                    assertThat(properties.getTaskQueue()).isEqualTo("tutor-runtime");
                    assertThat(properties.getModelTimeout()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(properties.getStorageTimeout()).isEqualTo(Duration.ofMillis(1));
                    assertThat(properties.getDispatchInterval()).isEqualTo(Duration.ofMinutes(1));
                    assertThat(properties.getReconciliationInterval()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.getShutdownTimeout()).isEqualTo(Duration.ofMillis(1));
                    assertThat(properties.getMaxConcurrentActivities()).isEqualTo(64);
                    assertThat(properties.getDispatchBatchSize()).isEqualTo(500);
                });
    }

    @Test
    void workerAndDispatcherRequireMasterSwitch() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setWorkerEnabled(true);
        assertThat(paths(properties)).contains("featureSwitchesValid");
        properties.setWorkerEnabled(false);
        properties.setDispatcherEnabled(true);
        assertThat(paths(properties)).contains("featureSwitchesValid");
        runner.withPropertyValues("app.agent.workflow.worker-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void blankNamesAreRejected() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setTemporalTarget(" ");
        properties.setNamespace("");
        properties.setTaskQueue(null);
        assertThat(paths(properties)).contains("temporalTarget", "namespace", "taskQueue");
    }

    @Test
    void invalidDurationBoundsAreRejected() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setModelTimeout(null);
        properties.setStorageTimeout(Duration.ZERO);
        properties.setDispatchInterval(Duration.ofNanos(999_999));
        properties.setReconciliationInterval(Duration.ofMinutes(5).plusNanos(1));
        properties.setShutdownTimeout(Duration.ofMinutes(5).plusMillis(1));
        assertThat(paths(properties)).contains("modelTimeoutValid", "storageTimeoutValid",
                "dispatchIntervalValid", "reconciliationIntervalValid", "shutdownTimeoutValid");
        properties.setModelTimeout(Duration.ofMinutes(2).plusMillis(1));
        properties.setStorageTimeout(Duration.ofMinutes(1).plusMillis(1));
        properties.setDispatchInterval(Duration.ofMinutes(1).plusMillis(1));
        assertThat(paths(properties)).contains("modelTimeoutValid", "storageTimeoutValid",
                "dispatchIntervalValid");
    }

    @Test
    void numericBoundsAreRejectedAndMinimumsAccepted() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setMaxConcurrentActivities(0);
        properties.setDispatchBatchSize(501);
        assertThat(paths(properties)).contains("maxConcurrentActivities", "dispatchBatchSize");
        properties.setMaxConcurrentActivities(65);
        properties.setDispatchBatchSize(0);
        assertThat(paths(properties)).contains("maxConcurrentActivities", "dispatchBatchSize");
        properties.setMaxConcurrentActivities(1);
        properties.setDispatchBatchSize(1);
        assertThat(validator.validate(properties)).isEmpty();
    }

    private Set<String> paths(WorkflowRuntimeProperties properties) {
        return validator.validate(properties).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WorkflowRuntimeProperties.class)
    static class TestConfiguration {
    }
}
