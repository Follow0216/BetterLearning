package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.MultiAgentModels.Attempt;
import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.Run;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.betterlearning.server.multiagent.TutorLedger;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.util.function.Supplier;

/** Idempotent Temporal lifecycle operations backed exclusively by the shared business ledger. */
public final class LedgerRunLifecycle implements RunLifecycleActivities {
    private final TutorLedger ledger;
    private final Clock clock;

    public LedgerRunLifecycle(TutorLedger ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    @Override
    public long begin(long accountId, String runId) {
        return safe(() -> beginFromLedger(accountId, runId));
    }

    private long beginFromLedger(long accountId, String runId) {
        Execution execution = ledger.execution(accountId, runId);
        if (execution.cancelRequestedAt() != null || execution.runDeadlineAt() == null
                || !execution.runDeadlineAt().isAfter(clock.instant()) || !current(execution)) {
            return 0;
        }
        if (execution.status() == Status.QUEUED) {
            execution = ledger.start(accountId, runId, execution.revision())
                    .orElseGet(() -> ledger.execution(accountId, runId));
        }
        return runnable(execution) ? execution.runDeadlineAt().toEpochMilli() : 0;
    }

    @Override
    public String nextStep(long accountId, String runId, int ordinal) {
        return safe(() -> nextStepFromLedger(accountId, runId));
    }

    private String nextStepFromLedger(long accountId, String runId) {
        Execution execution = ledger.execution(accountId, runId);
        // This token identifies a pipeline invocation. Actual role step IDs remain in the P1 ledger.
        return runnable(execution) ? runId : null;
    }

    @Override
    public boolean recoverStep(long accountId, String runId, String stepId) {
        return safe(() -> recoverFromLedger(accountId, runId));
    }

    private boolean recoverFromLedger(long accountId, String runId) {
        Execution execution = ledger.execution(accountId, runId);
        if (!runnable(execution)) {
            return false;
        }
        Run run = ledger.detail(accountId, execution.questionId(), execution.requestId()).runs().stream()
                .filter(candidate -> runId.equals(candidate.runId())).findFirst().orElseThrow();
        for (Attempt attempt : run.attempts()) {
            if ("CALL_INTENT".equals(attempt.providerCallState())) {
                ledger.unknown(accountId, runId, attempt.attemptId(), attempt.fence());
                return false;
            }
            if ("UNKNOWN".equals(attempt.providerCallState())) {
                ledger.fail(accountId, runId, execution.revision(), "OUTCOME_UNKNOWN");
                return false;
            }
        }
        // Re-entering the pipeline is safe only with no ambiguous external call. The shared executor
        // supplies CONFIRMED outputs and only sends roles whose attempt remains NOT_SENT.
        return true;
    }

    @Override
    public void settle(long accountId, String runId, String reason) {
        safe(() -> {
            settleFromLedger(accountId, runId, reason);
            return null;
        });
    }

    private void settleFromLedger(long accountId, String runId, String reason) {
        Execution execution = ledger.execution(accountId, runId);
        if ("CANCELLED".equals(reason) || "TIMED_OUT".equals(reason)) {
            ledger.reconcileDeadlines(clock.instant(), 1000);
            return;
        }
        if (!runnable(execution)) {
            return;
        }
        ledger.fail(accountId, runId, execution.revision(),
                "PIPELINE_LIMIT".equals(reason) ? "BUDGET_EXCEEDED" : "MODEL_UNAVAILABLE");
    }

    private boolean runnable(Execution execution) {
        return execution.status() == Status.RUNNING && execution.cancelRequestedAt() == null
                && execution.runDeadlineAt() != null && execution.runDeadlineAt().isAfter(clock.instant())
                && current(execution);
    }

    private boolean current(Execution execution) {
        return execution.runId().equals(ledger.getByRequestId(execution.accountId(), execution.requestId()).currentRunId());
    }

    private static <T> T safe(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException unavailable) {
            // Temporal history must never carry SQL, provider or source text from a port exception.
            throw ApplicationFailure.newFailure("Ledger operation unavailable", "LEDGER_UNAVAILABLE");
        }
    }
}
