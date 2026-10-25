package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.Mode;
import com.betterlearning.server.multiagent.MultiAgentModels.PipelineResult;
import com.betterlearning.server.multiagent.MultiAgentModels.Request;
import com.betterlearning.server.multiagent.MultiAgentModels.ResultDraft;
import com.betterlearning.server.multiagent.MultiAgentModels.Role;
import com.betterlearning.server.multiagent.MultiAgentModels.Snapshot;
import com.betterlearning.server.multiagent.MultiAgentModels.SnapshotInput;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.betterlearning.server.multiagent.MultiAgentModels.StepInvocation;
import com.betterlearning.server.multiagent.RolePipeline;
import com.betterlearning.server.multiagent.SnapshotPort;
import com.betterlearning.server.multiagent.StepExecutor;
import com.betterlearning.server.multiagent.StepExecutorFactory;
import com.betterlearning.server.multiagent.TutorLedger;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure port tests; these mocks do not prove PostgreSQL CAS or provider billing behavior. */
class PipelineRunActivityTests {
    private static final long ACCOUNT = 42L;
    private static final String REQUEST = "request-1";
    private static final String QUESTION = "question-1";
    private static final String RUN = "run-1";
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final StepExecutor DURABLE = (invocation, action) -> action.call();

    @Test
    void verifiedResultPublishesOnlyAgainstLatestRevision() {
        TutorLedger ledger = mock(TutorLedger.class);
        SnapshotPort snapshots = mock(SnapshotPort.class);
        RolePipeline pipeline = mock(RolePipeline.class);
        StepExecutorFactory executors = mock(StepExecutorFactory.class);
        when(ledger.execution(ACCOUNT, RUN)).thenReturn(active(3), active(4));
        when(ledger.getByRequestId(ACCOUNT, REQUEST)).thenReturn(request(RUN));
        when(ledger.evidence(ACCOUNT, RUN)).thenReturn(List.of());
        when(snapshots.load(ACCOUNT, REQUEST)).thenReturn(new Snapshot("file", "question", List.of()));
        when(executors.forRun(ACCOUNT, RUN)).thenReturn(DURABLE);
        when(pipeline.execute(any(), any())).thenReturn(
                new PipelineResult(true, "verified answer", List.of("evidence-1"), null));

        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            activity(ledger, snapshots, pipeline, executors, deadline).executeStep(ACCOUNT, RUN, RUN, 50);
        }

        ArgumentCaptor<ResultDraft> draft = ArgumentCaptor.forClass(ResultDraft.class);
        verify(ledger).publish(eq(ACCOUNT), eq(RUN), eq(4L), draft.capture());
        assertEquals("verified answer", draft.getValue().bodyMarkdown());
        assertEquals(List.of("evidence-1"), draft.getValue().evidenceIds());
        verify(ledger, never()).fail(eq(ACCOUNT), eq(RUN), anyLong(), any());
    }

    @Test
    void staleOrExpiredRunDoesNotEnterPipeline() {
        TutorLedger stale = mock(TutorLedger.class);
        SnapshotPort snapshots = mock(SnapshotPort.class);
        RolePipeline pipeline = mock(RolePipeline.class);
        StepExecutorFactory executors = mock(StepExecutorFactory.class);
        when(stale.execution(ACCOUNT, RUN)).thenReturn(active(3));
        when(stale.getByRequestId(ACCOUNT, REQUEST)).thenReturn(request("retry-run"));
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            activity(stale, snapshots, pipeline, executors, deadline).executeStep(ACCOUNT, RUN, RUN, 50);
        }
        verify(snapshots, never()).load(ACCOUNT, REQUEST);
        verify(pipeline, never()).execute(any(), any());

        TutorLedger expired = mock(TutorLedger.class);
        when(expired.execution(ACCOUNT, RUN)).thenReturn(execution(3, null, NOW.minusSeconds(1)));
        when(expired.getByRequestId(ACCOUNT, REQUEST)).thenReturn(request(RUN));
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            activity(expired, snapshots, pipeline, executors, deadline).executeStep(ACCOUNT, RUN, RUN, 50);
        }
        verify(snapshots, never()).load(ACCOUNT, REQUEST);
        verify(pipeline, never()).execute(any(), any());
    }

    @Test
    void cancellationOrRetryPointerChangeAfterPipelinePreventsPublish() {
        SnapshotPort snapshots = mock(SnapshotPort.class);
        RolePipeline pipeline = mock(RolePipeline.class);
        StepExecutorFactory executors = mock(StepExecutorFactory.class);
        when(snapshots.load(ACCOUNT, REQUEST)).thenReturn(new Snapshot("file", "question", List.of()));
        when(executors.forRun(ACCOUNT, RUN)).thenReturn(DURABLE);
        when(pipeline.execute(any(), any())).thenReturn(new PipelineResult(true, "answer", List.of(), null));

        TutorLedger cancelled = mock(TutorLedger.class);
        when(cancelled.execution(ACCOUNT, RUN)).thenReturn(active(3), execution(4,
                NOW, NOW.plusSeconds(60)));
        when(cancelled.getByRequestId(ACCOUNT, REQUEST)).thenReturn(request(RUN));
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            activity(cancelled, snapshots, pipeline, executors, deadline).executeStep(ACCOUNT, RUN, RUN, 50);
        }
        verify(cancelled, never()).publish(anyLong(), any(), anyLong(), any());

        TutorLedger retry = mock(TutorLedger.class);
        when(retry.execution(ACCOUNT, RUN)).thenReturn(active(3), active(4));
        when(retry.getByRequestId(ACCOUNT, REQUEST)).thenReturn(request(RUN), request("retry-run"));
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            activity(retry, snapshots, pipeline, executors, deadline).executeStep(ACCOUNT, RUN, RUN, 50);
        }
        verify(retry, never()).publish(anyLong(), any(), anyLong(), any());
    }

    @Test
    void reviewFailureFailsLatestRevisionWithoutPublishing() {
        TutorLedger ledger = readyLedger();
        SnapshotPort snapshots = readySnapshots();
        RolePipeline pipeline = mock(RolePipeline.class);
        StepExecutorFactory executors = readyExecutors();
        when(pipeline.execute(any(), any())).thenReturn(
                new PipelineResult(false, null, List.of(), "REVIEW_REJECTED"));
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            activity(ledger, snapshots, pipeline, executors, deadline).executeStep(ACCOUNT, RUN, RUN, 50);
        }
        verify(ledger).fail(ACCOUNT, RUN, 4, "REVIEW_REJECTED");
        verify(ledger, never()).publish(anyLong(), any(), anyLong(), any());
    }

    @Test
    void rawProviderExceptionCannotReachTemporalFailureCause() {
        TutorLedger ledger = readyLedger();
        SnapshotPort snapshots = readySnapshots();
        RolePipeline pipeline = mock(RolePipeline.class);
        StepExecutorFactory executors = readyExecutors();
        when(pipeline.execute(any(), any())).thenAnswer(invocation -> {
            StepExecutor steps = invocation.getArgument(1);
            steps.execute(new StepInvocation(Role.SOLVE, 1, "digest"), () -> {
                throw new IllegalStateException("secret-provider-response");
            });
            throw new AssertionError("provider failure was not propagated");
        });
        ApplicationFailure failure;
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            PipelineRunActivity activity = activity(ledger, snapshots, pipeline, executors, deadline);
            failure = assertThrows(ApplicationFailure.class,
                    () -> activity.executeStep(ACCOUNT, RUN, RUN, 50));
        }
        assertEquals("ROLE_EXECUTION_STOPPED", failure.getType());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("secret-provider-response"));
        verify(ledger, never()).publish(anyLong(), any(), anyLong(), any());
    }

    private static PipelineRunActivity activity(TutorLedger ledger, SnapshotPort snapshots,
            RolePipeline pipeline, StepExecutorFactory executors, ProviderCallDeadline deadline) {
        return new PipelineRunActivity(ledger, snapshots, pipeline, executors, deadline, CLOCK, () -> () -> { });
    }

    private static TutorLedger readyLedger() {
        TutorLedger ledger = mock(TutorLedger.class);
        when(ledger.execution(ACCOUNT, RUN)).thenReturn(active(3), active(4));
        when(ledger.getByRequestId(ACCOUNT, REQUEST)).thenReturn(request(RUN));
        when(ledger.evidence(ACCOUNT, RUN)).thenReturn(List.of());
        return ledger;
    }

    private static SnapshotPort readySnapshots() {
        SnapshotPort snapshots = mock(SnapshotPort.class);
        when(snapshots.load(ACCOUNT, REQUEST)).thenReturn(new Snapshot("file", "question", List.of()));
        return snapshots;
    }

    private static StepExecutorFactory readyExecutors() {
        StepExecutorFactory executors = mock(StepExecutorFactory.class);
        when(executors.forRun(ACCOUNT, RUN)).thenReturn(DURABLE);
        return executors;
    }

    private static Execution active(long revision) {
        return execution(revision, null, NOW.plusSeconds(60));
    }

    private static Execution execution(long revision, Instant cancelledAt, Instant deadline) {
        return new Execution(ACCOUNT, REQUEST, QUESTION, RUN, Mode.HINT, "prompt", "snapshot",
                "source", 1, Status.RUNNING, revision, NOW.plusSeconds(30),
                deadline, cancelledAt, 0);
    }

    private static Request request(String currentRunId) {
        return new Request(REQUEST, QUESTION, "prompt", Mode.HINT,
                new SnapshotInput("NEW_CAPTURE", null), null, "snapshot", currentRunId,
                Status.RUNNING, 1, NOW, NOW);
    }
}
