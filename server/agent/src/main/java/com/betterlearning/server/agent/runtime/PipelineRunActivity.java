package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.PipelineContext;
import com.betterlearning.server.multiagent.MultiAgentModels.PipelineResult;
import com.betterlearning.server.multiagent.MultiAgentModels.ResultDraft;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.betterlearning.server.multiagent.RolePipeline;
import com.betterlearning.server.multiagent.SnapshotPort;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.multiagent.StepExecutorFactory;
import com.betterlearning.server.multiagent.TutorLedger;
import io.temporal.failure.ApplicationFailure;
import java.time.Duration;
import java.time.Clock;
import java.util.function.Supplier;

/** Sources and role output never cross the Activity result boundary into Temporal history. */
public final class PipelineRunActivity implements RunStepActivities {
    private final TutorLedger ledger;
    private final SnapshotPort snapshots;
    private final RolePipeline pipeline;
    private final StepExecutorFactory executors;
    private final ProviderCallDeadline deadlines;
    private final Clock clock;
    private final Supplier<Runnable> heartbeat;

    public PipelineRunActivity(TutorLedger ledger, SnapshotPort snapshots, RolePipeline pipeline,
            StepExecutorFactory executors, ProviderCallDeadline deadlines) {
        this(ledger, snapshots, pipeline, executors, deadlines, Clock.systemUTC(), RuntimeActivityHeartbeat::start);
    }

    PipelineRunActivity(TutorLedger ledger, SnapshotPort snapshots, RolePipeline pipeline,
            StepExecutorFactory executors, ProviderCallDeadline deadlines, Clock clock, Supplier<Runnable> heartbeat) {
        this.ledger = ledger;
        this.snapshots = snapshots;
        this.pipeline = pipeline;
        this.executors = executors;
        this.deadlines = deadlines;
        this.clock = clock;
        this.heartbeat = heartbeat;
    }

    @Override
    public void executeStep(long accountId, String runId, String stepId, long modelTimeoutMillis) {
        Runnable stopHeartbeat = heartbeat.get();
        try {
            executePipeline(accountId, runId, modelTimeoutMillis);
        } catch (StepExecutionException stopped) {
            // Only a fixed reason crosses the SDK failure boundary; no supplier diagnostics/cause.
            throw ApplicationFailure.newNonRetryableFailure("Pipeline stopped; inspect the run ledger",
                    "ROLE_EXECUTION_STOPPED");
        } catch (RuntimeException unavailable) {
            throw ApplicationFailure.newNonRetryableFailure("Pipeline unavailable; inspect the run ledger",
                    "PIPELINE_UNAVAILABLE");
        } finally {
            stopHeartbeat.run();
        }
    }

    private void executePipeline(long accountId, String runId, long modelTimeoutMillis) {
        Execution execution = ledger.execution(accountId, runId);
        if (!active(execution)) {
            return;
        }
        PipelineContext context = new PipelineContext(execution, snapshots.load(accountId, execution.requestId()),
                ledger.evidence(accountId, runId));
        PipelineResult result = pipeline.execute(context,
                deadlines.bind(executors.forRun(accountId, runId), Duration.ofMillis(modelTimeoutMillis)));
        Execution latest = ledger.execution(accountId, runId);
        if (!active(latest)) {
            return;
        }
        if (result == null || !result.verified()) {
            String reason = result != null && "BUDGET_EXCEEDED".equals(result.reason())
                    ? "BUDGET_EXCEEDED" : "REVIEW_REJECTED";
            ledger.fail(accountId, runId, latest.revision(), reason);
            return;
        }
        ledger.publish(accountId, runId, latest.revision(),
                new ResultDraft(result.bodyMarkdown(), result.evidenceIds()));
    }

    private boolean active(Execution execution) {
        return execution.status() == Status.RUNNING && execution.cancelRequestedAt() == null
                && execution.runDeadlineAt() != null && execution.runDeadlineAt().isAfter(clock.instant())
                && execution.runId().equals(ledger.getByRequestId(execution.accountId(), execution.requestId()).currentRunId());
    }
}
