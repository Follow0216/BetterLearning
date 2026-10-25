package com.betterlearning.server.agent.runtime;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemporalExecutionPolicyTests {
    @Test
    void paidActivityHasExactlyOneAttemptAndBoundedTimeout() {
        var options = TemporalExecutionPolicy.modelActivity(Duration.ofSeconds(120));
        assertEquals(1, options.getRetryOptions().getMaximumAttempts());
        assertEquals(Duration.ofSeconds(120), options.getStartToCloseTimeout());
        assertEquals(Duration.ofSeconds(150), options.getScheduleToCloseTimeout());
        assertEquals(ActivityCancellationType.TRY_CANCEL, options.getCancellationType());
    }

    @Test
    void databaseRetriesAreBoundedAndCannotBecomeModelDefaults() {
        var options = TemporalExecutionPolicy.storageActivity(Duration.ofSeconds(10));
        assertEquals(3, options.getRetryOptions().getMaximumAttempts());
        assertEquals(Duration.ofSeconds(45), options.getScheduleToCloseTimeout());
        assertEquals(Duration.ofSeconds(2), options.getRetryOptions().getMaximumInterval());
    }

    @Test
    void multiRoleActivityHasRunBoundAndStillCannotAutomaticallyRetryPaidWork() {
        var options = TemporalExecutionPolicy.pipelineActivity(Duration.ofMinutes(10));
        assertEquals(1, options.getRetryOptions().getMaximumAttempts());
        assertEquals(Duration.ofMinutes(10), options.getStartToCloseTimeout());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalExecutionPolicy.pipelineActivity(Duration.ofMinutes(11)));
    }

    @Test
    void workflowCannotRestartClosedRunOrReplaceRunningRun() {
        String runId = UUID.randomUUID().toString();
        var options = TemporalExecutionPolicy.workflow(runId, "test-only");
        assertEquals(runId, options.getWorkflowId());
        assertEquals(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE,
                options.getWorkflowIdReusePolicy());
        assertEquals(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL,
                options.getWorkflowIdConflictPolicy());
        assertNull(options.getRetryOptions());
    }

    @Test
    void rejectsInvalidIdentityAndTimeouts() {
        assertThrows(IllegalArgumentException.class,
                () -> TemporalExecutionPolicy.workflow("not-a-run", "queue"));
        assertThrows(IllegalArgumentException.class,
                () -> TemporalExecutionPolicy.workflow(UUID.randomUUID().toString(), " "));
        assertThrows(IllegalArgumentException.class,
                () -> TemporalExecutionPolicy.modelActivity(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> TemporalExecutionPolicy.modelActivity(Duration.ofSeconds(121)));
        assertThrows(IllegalArgumentException.class,
                () -> TemporalExecutionPolicy.storageActivity(Duration.ofSeconds(-1)));
    }
}
