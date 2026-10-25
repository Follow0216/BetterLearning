package com.betterlearning.server.agent.runtime;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Temporal 执行器的部署参数；收费调用次数和重试次数由运行时代码固定。 */
@Validated
@ConfigurationProperties(prefix = "app.agent.workflow")
public class WorkflowRuntimeProperties {
    private boolean enabled;
    private boolean workerEnabled;
    private boolean dispatcherEnabled;

    @NotBlank
    private String temporalTarget = "127.0.0.1:7233";

    @NotBlank
    private String namespace = "default";

    @NotBlank
    private String taskQueue = "betterlearning-tutor-v1";

    private Duration modelTimeout = Duration.ofMinutes(2);
    private Duration storageTimeout = Duration.ofSeconds(10);
    private Duration dispatchInterval = Duration.ofSeconds(1);
    private Duration reconciliationInterval = Duration.ofSeconds(5);
    private Duration shutdownTimeout = Duration.ofSeconds(10);

    @Min(1)
    @Max(64)
    private int maxConcurrentActivities = 4;

    @Min(1)
    @Max(500)
    private int dispatchBatchSize = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public boolean isDispatcherEnabled() {
        return dispatcherEnabled;
    }

    public void setDispatcherEnabled(boolean dispatcherEnabled) {
        this.dispatcherEnabled = dispatcherEnabled;
    }

    public String getTemporalTarget() {
        return temporalTarget;
    }

    public void setTemporalTarget(String temporalTarget) {
        this.temporalTarget = temporalTarget;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getTaskQueue() {
        return taskQueue;
    }

    public void setTaskQueue(String taskQueue) {
        this.taskQueue = taskQueue;
    }

    public Duration getModelTimeout() {
        return modelTimeout;
    }

    public void setModelTimeout(Duration modelTimeout) {
        this.modelTimeout = modelTimeout;
    }

    public Duration getStorageTimeout() {
        return storageTimeout;
    }

    public void setStorageTimeout(Duration storageTimeout) {
        this.storageTimeout = storageTimeout;
    }

    public Duration getDispatchInterval() {
        return dispatchInterval;
    }

    public void setDispatchInterval(Duration dispatchInterval) {
        this.dispatchInterval = dispatchInterval;
    }

    public Duration getReconciliationInterval() {
        return reconciliationInterval;
    }

    public void setReconciliationInterval(Duration reconciliationInterval) {
        this.reconciliationInterval = reconciliationInterval;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public int getMaxConcurrentActivities() {
        return maxConcurrentActivities;
    }

    public void setMaxConcurrentActivities(int maxConcurrentActivities) {
        this.maxConcurrentActivities = maxConcurrentActivities;
    }

    public int getDispatchBatchSize() {
        return dispatchBatchSize;
    }

    public void setDispatchBatchSize(int dispatchBatchSize) {
        this.dispatchBatchSize = dispatchBatchSize;
    }

    @AssertTrue(message = "worker/dispatcher requires app.agent.workflow.enabled")
    public boolean isFeatureSwitchesValid() {
        return enabled || (!workerEnabled && !dispatcherEnabled);
    }

    @AssertTrue(message = "model-timeout must be between 1 millisecond and 2 minutes")
    public boolean isModelTimeoutValid() {
        return validDuration(modelTimeout, Duration.ofMinutes(2));
    }

    @AssertTrue(message = "storage-timeout must be between 1 millisecond and 1 minute")
    public boolean isStorageTimeoutValid() {
        return validDuration(storageTimeout, Duration.ofMinutes(1));
    }

    @AssertTrue(message = "dispatch-interval must be between 1 millisecond and 1 minute")
    public boolean isDispatchIntervalValid() {
        return validDuration(dispatchInterval, Duration.ofMinutes(1));
    }

    @AssertTrue(message = "reconciliation-interval must be between 1 millisecond and 5 minutes")
    public boolean isReconciliationIntervalValid() {
        return validDuration(reconciliationInterval, Duration.ofMinutes(5));
    }

    @AssertTrue(message = "shutdown-timeout must be between 1 millisecond and 5 minutes")
    public boolean isShutdownTimeoutValid() {
        return validDuration(shutdownTimeout, Duration.ofMinutes(5));
    }

    private static boolean validDuration(Duration value, Duration maximum) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0
                && value.compareTo(maximum) <= 0;
    }
}
