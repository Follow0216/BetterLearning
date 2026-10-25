package com.betterlearning.server.agent.runtime;

import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/** Deterministic scheduling only; database state remains authoritative. */
public class TutorRunWorkflowImpl implements TutorRunWorkflow {
    private boolean cancellationRequested;

    @Override
    public void execute(long accountId, String runId, long modelTimeoutMillis, long storageTimeoutMillis) {
        if (accountId <= 0) {
            throw ApplicationFailure.newNonRetryableFailure("accountId must be positive", "INVALID_ACCOUNT_ID");
        }
        RunLifecycleActivities lifecycle = Workflow.newActivityStub(RunLifecycleActivities.class,
                TemporalExecutionPolicy.storageActivity(Duration.ofMillis(storageTimeoutMillis)));
        // Validate the per-provider limit independently of the multi-role Activity duration.
        TemporalExecutionPolicy.modelActivity(Duration.ofMillis(modelTimeoutMillis));
        long deadline = lifecycle.begin(accountId, runId);
        if (deadline == 0) {
            return;
        }
        long remaining = deadline - Workflow.currentTimeMillis();
        if (remaining <= 0 || cancellationRequested) {
            settleDetached(lifecycle, accountId, runId, cancellationRequested ? "CANCELLED" : "TIMED_OUT");
            return;
        }
        RunStepActivities steps = Workflow.newActivityStub(RunStepActivities.class,
                TemporalExecutionPolicy.pipelineActivity(Duration.ofMillis(Math.min(remaining, 600_000L))));
        CompletablePromise<Void> execution = Workflow.newPromise();
        CancellationScope scope = Workflow.newCancellationScope(() ->
                execution.completeFrom(Async.procedure(() -> executeSteps(lifecycle, steps, accountId, runId, modelTimeoutMillis))));
        scope.run();
        execution.handle((value, failure) -> null);
        boolean completed = Workflow.await(Duration.ofMillis(remaining),
                () -> cancellationRequested || execution.isCompleted());
        if (cancellationRequested || !completed) {
            scope.cancel();
            settleDetached(lifecycle, accountId, runId, cancellationRequested ? "CANCELLED" : "TIMED_OUT");
            return;
        }
        try {
            execution.get();
        } catch (ActivityFailure unavailableLedger) {
            // Reconciliation can settle the run if the independent database path is unavailable too.
            settleDetached(lifecycle, accountId, runId, "EXECUTION_FAILED");
        }
    }

    private void executeSteps(RunLifecycleActivities lifecycle, RunStepActivities steps,
            long accountId, String runId, long modelTimeoutMillis) {
        // A separate DB generation budget is mandatory. This bound also contains malformed pipelines.
        for (int ordinal = 0; ordinal < 16; ordinal++) {
            if (cancellationRequested) {
                return;
            }
            String stepId = lifecycle.nextStep(accountId, runId, ordinal);
            if (stepId == null || cancellationRequested) {
                return;
            }
            try {
                steps.executeStep(accountId, runId, stepId, modelTimeoutMillis);
            } catch (ActivityFailure failure) {
                if (!lifecycle.recoverStep(accountId, runId, stepId)) {
                    return;
                }
                // Includes capacity admission and an acknowledged output whose Activity ack was lost.
                // Never busy-loop a safe re-entry while a cancelled external call still owns a DB lease.
                Workflow.sleep(Duration.ofSeconds(Math.min(30, 1L << Math.min(ordinal, 5))));
            }
        }
        lifecycle.settle(accountId, runId, "PIPELINE_LIMIT");
    }

    private void settleDetached(RunLifecycleActivities lifecycle, long accountId, String runId, String reason) {
        Workflow.newDetachedCancellationScope(() -> lifecycle.settle(accountId, runId, reason)).run();
    }

    @Override
    public void requestCancellation() {
        cancellationRequested = true;
    }
}
