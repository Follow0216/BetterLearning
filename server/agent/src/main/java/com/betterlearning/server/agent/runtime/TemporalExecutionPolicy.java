package com.betterlearning.server.agent.runtime;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.UUID;

/** Fixed safety policy for the version-one orchestration protocol. */
public final class TemporalExecutionPolicy {
    public static final int MAX_GENERATION_CALLS = 8;
    public static final int MAX_REWORKS = 1;
    public static final String WORKFLOW_TYPE = "BetterLearningTutorV1";

    private TemporalExecutionPolicy() {
    }

    /** Paid calls must never inherit the SDK's retry defaults. */
    public static ActivityOptions modelActivity(Duration timeout) {
        bounded(timeout, Duration.ofMinutes(2));
        return singleAttemptActivity(timeout);
    }

    /** A role pipeline has several calls, each separately bounded and journaled by StepExecutor. */
    public static ActivityOptions pipelineActivity(Duration timeout) {
        bounded(timeout, Duration.ofMinutes(10));
        return singleAttemptActivity(timeout).toBuilder().setHeartbeatTimeout(Duration.ofSeconds(10)).build();
    }

    private static ActivityOptions singleAttemptActivity(Duration timeout) {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(positive(timeout))
                .setScheduleToCloseTimeout(timeout.plusSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .setCancellationType(ActivityCancellationType.TRY_CANCEL)
                .build();
    }

    /** Only idempotent database operations and reads use this bounded retry policy. */
    public static ActivityOptions storageActivity(Duration timeout) {
        bounded(timeout, Duration.ofMinutes(1));
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(positive(timeout))
                .setScheduleToCloseTimeout(timeout.multipliedBy(4).plusSeconds(5))
                .setCancellationType(ActivityCancellationType.ABANDON)
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumInterval(Duration.ofSeconds(2))
                        .build())
                .build();
    }

    public static WorkflowOptions workflow(String runId, String taskQueue) {
        if (!UUID.fromString(runId).toString().equals(runId)) {
            throw new IllegalArgumentException("runId must be a canonical UUID");
        }
        if (taskQueue == null || taskQueue.isBlank()) {
            throw new IllegalArgumentException("taskQueue is required");
        }
        return WorkflowOptions.newBuilder()
                .setWorkflowId(runId)
                .setTaskQueue(taskQueue)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .build();
    }

    private static Duration positive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return duration;
    }

    private static void bounded(Duration duration, Duration maximum) {
        if (positive(duration).compareTo(maximum) > 0) {
            throw new IllegalArgumentException("timeout exceeds runtime safety limit");
        }
    }
}
