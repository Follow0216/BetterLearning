package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.MultiAgentModels.Attempt;
import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.Mode;
import com.betterlearning.server.multiagent.MultiAgentModels.Request;
import com.betterlearning.server.multiagent.MultiAgentModels.RequestDetail;
import com.betterlearning.server.multiagent.MultiAgentModels.Run;
import com.betterlearning.server.multiagent.MultiAgentModels.SnapshotInput;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.betterlearning.server.multiagent.TutorLedger;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Shared ledger port tests; these mocks do not prove database transaction behavior. */
class LedgerRunLifecycleTests {
    private static final long ACCOUNT = 42L;
    private static final String REQUEST = "request-1";
    private static final String QUESTION = "question-1";
    private static final String RUN = "run-1";
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void beginStartsOnlyCurrentQueuedRunWithTrustedAccountAndRevision() {
        TutorLedger ledger = mock(TutorLedger.class);
        Execution queued = execution(RUN, Status.QUEUED, 7, null, NOW.plusSeconds(60));
        Execution running = execution(RUN, Status.RUNNING, 8, null, NOW.plusSeconds(60));
        when(ledger.execution(ACCOUNT, RUN)).thenReturn(queued);
        when(ledger.getByRequestId(ACCOUNT, REQUEST)).thenReturn(request(RUN));
        when(ledger.start(ACCOUNT, RUN, 7)).thenReturn(Optional.of(running));

        assertEquals(NOW.plusSeconds(60).toEpochMilli(), new LedgerRunLifecycle(ledger, CLOCK).begin(ACCOUNT, RUN));
        verify(ledger).execution(ACCOUNT, RUN);
        verify(ledger).start(ACCOUNT, RUN, 7);
    }

    @Test
    void staleQueuedRunCannotStartAndCancelledOrExpiredRunHasNoStep() {
        TutorLedger staleLedger = mock(TutorLedger.class);
        when(staleLedger.execution(ACCOUNT, RUN)).thenReturn(
                execution(RUN, Status.QUEUED, 7, null, NOW.plusSeconds(60)));
        when(staleLedger.getByRequestId(ACCOUNT, REQUEST)).thenReturn(request("retry-run"));
        LedgerRunLifecycle stale = new LedgerRunLifecycle(staleLedger, CLOCK);
        assertEquals(0, stale.begin(ACCOUNT, RUN));
        assertNull(stale.nextStep(ACCOUNT, RUN, 0));
        verify(staleLedger, never()).start(ACCOUNT, RUN, 7);

        TutorLedger cancelledLedger = mock(TutorLedger.class);
        when(cancelledLedger.execution(ACCOUNT, RUN)).thenReturn(
                execution(RUN, Status.RUNNING, 8, NOW, NOW.plusSeconds(60)));
        LedgerRunLifecycle cancelled = new LedgerRunLifecycle(cancelledLedger, CLOCK);
        assertEquals(0, cancelled.begin(ACCOUNT, RUN));
        assertNull(cancelled.nextStep(ACCOUNT, RUN, 0));

        TutorLedger expiredLedger = mock(TutorLedger.class);
        when(expiredLedger.execution(ACCOUNT, RUN)).thenReturn(
                execution(RUN, Status.RUNNING, 8, null, NOW));
        LedgerRunLifecycle expired = new LedgerRunLifecycle(expiredLedger, CLOCK);
        assertEquals(0, expired.begin(ACCOUNT, RUN));
        assertNull(expired.nextStep(ACCOUNT, RUN, 0));
    }

    @Test
    void callIntentRecoveryMarksUnknownAndRefusesPipelineReentry() {
        TutorLedger ledger = recoverLedger("CALL_INTENT");
        assertFalse(new LedgerRunLifecycle(ledger, CLOCK).recoverStep(ACCOUNT, RUN, "step-1"));
        verify(ledger).unknown(ACCOUNT, RUN, "attempt-1", 12);
        verify(ledger, never()).fail(ACCOUNT, RUN, 9, "OUTCOME_UNKNOWN");
    }

    @Test
    void confirmedOnlyRecoveryMayReenterDurableExecutor() {
        TutorLedger ledger = recoverLedger("CONFIRMED");
        assertTrue(new LedgerRunLifecycle(ledger, CLOCK).recoverStep(ACCOUNT, RUN, "step-1"));
        verify(ledger, never()).unknown(ACCOUNT, RUN, "attempt-1", 12);
        verify(ledger, never()).fail(ACCOUNT, RUN, 9, "OUTCOME_UNKNOWN");
    }

    @Test
    void persistedUnknownAttemptFailsRunWithoutReenteringPipeline() {
        TutorLedger ledger = recoverLedger("UNKNOWN");
        assertFalse(new LedgerRunLifecycle(ledger, CLOCK).recoverStep(ACCOUNT, RUN, "step-1"));
        verify(ledger).fail(ACCOUNT, RUN, 9, "OUTCOME_UNKNOWN");
        verify(ledger, never()).unknown(ACCOUNT, RUN, "attempt-1", 12);
    }

    @Test
    void beginPortFailureIsRetryableAndDoesNotEnterTemporalHistoryWithCause() {
        TutorLedger ledger = mock(TutorLedger.class);
        when(ledger.execution(ACCOUNT, RUN)).thenReturn(
                execution(RUN, Status.QUEUED, 7, null, NOW.plusSeconds(60)));
        when(ledger.getByRequestId(ACCOUNT, REQUEST)).thenReturn(request(RUN));
        when(ledger.start(ACCOUNT, RUN, 7)).thenThrow(sensitive());

        assertSanitized(() -> new LedgerRunLifecycle(ledger, CLOCK).begin(ACCOUNT, RUN));
    }

    @Test
    void nextStepPortFailureIsRetryableAndDoesNotEnterTemporalHistoryWithCause() {
        TutorLedger ledger = mock(TutorLedger.class);
        when(ledger.execution(ACCOUNT, RUN)).thenReturn(
                execution(RUN, Status.RUNNING, 9, null, NOW.plusSeconds(60)));
        when(ledger.getByRequestId(ACCOUNT, REQUEST)).thenThrow(sensitive());

        assertSanitized(() -> new LedgerRunLifecycle(ledger, CLOCK).nextStep(ACCOUNT, RUN, 0));
    }

    @Test
    void recoverPortFailureIsRetryableAndDoesNotEnterTemporalHistoryWithCause() {
        TutorLedger ledger = recoverLedger("CONFIRMED");
        when(ledger.detail(ACCOUNT, QUESTION, REQUEST)).thenThrow(sensitive());

        assertSanitized(() -> new LedgerRunLifecycle(ledger, CLOCK).recoverStep(ACCOUNT, RUN, "step-1"));
    }

    @Test
    void settlePortFailureIsRetryableAndDoesNotEnterTemporalHistoryWithCause() {
        TutorLedger ledger = mock(TutorLedger.class);
        when(ledger.execution(ACCOUNT, RUN)).thenReturn(
                execution(RUN, Status.RUNNING, 9, NOW, NOW.plusSeconds(60)));
        when(ledger.reconcileDeadlines(NOW, 1000)).thenThrow(sensitive());

        assertSanitized(() -> new LedgerRunLifecycle(ledger, CLOCK).settle(ACCOUNT, RUN, "CANCELLED"));
    }

    private static void assertSanitized(Executable operation) {
        ApplicationFailure failure = assertThrows(ApplicationFailure.class, operation);
        assertEquals("LEDGER_UNAVAILABLE", failure.getType());
        assertFalse(failure.isNonRetryable());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("secret-sql-and-student-text"));
    }

    private static IllegalStateException sensitive() {
        return new IllegalStateException("secret-sql-and-student-text");
    }

    private static TutorLedger recoverLedger(String providerState) {
        TutorLedger ledger = mock(TutorLedger.class);
        when(ledger.execution(ACCOUNT, RUN)).thenReturn(
                execution(RUN, Status.RUNNING, 9, null, NOW.plusSeconds(60)));
        when(ledger.getByRequestId(ACCOUNT, REQUEST)).thenReturn(request(RUN));
        Attempt attempt = new Attempt("attempt-1", "step-1", 1, "RUNNING", providerState,
                12, NOW, null, null, null);
        Run run = new Run(RUN, REQUEST, 1, null, Status.RUNNING, 9, null, null,
                List.of(), List.of(attempt));
        when(ledger.detail(ACCOUNT, QUESTION, REQUEST)).thenReturn(
                new RequestDetail(request(RUN), run, List.of(run), null));
        return ledger;
    }

    private static Execution execution(String runId, Status status, long revision,
            Instant cancelledAt, Instant deadline) {
        return new Execution(ACCOUNT, REQUEST, QUESTION, runId, Mode.HINT, "prompt", "snapshot",
                "source", 1, status, revision, NOW.plusSeconds(30), deadline, cancelledAt, 0);
    }

    private static Request request(String currentRunId) {
        return new Request(REQUEST, QUESTION, "prompt", Mode.HINT,
                new SnapshotInput("NEW_CAPTURE", null), null, "snapshot", currentRunId,
                Status.RUNNING, 1, NOW, NOW);
    }
}
