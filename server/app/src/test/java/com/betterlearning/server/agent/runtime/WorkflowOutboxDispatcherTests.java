package com.betterlearning.server.agent.runtime;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.Mode;
import com.betterlearning.server.multiagent.MultiAgentModels.OutboxEvent;
import com.betterlearning.server.multiagent.MultiAgentModels.Request;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.betterlearning.server.multiagent.TutorLedger;

import io.temporal.client.WorkflowNotFoundException;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowOutboxDispatcherTests {
    private static final long ACCOUNT_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final Set<String> RUN_KINDS = Set.of("RUN_START", "RUN_CANCEL");

    @Test
    void pullsOnlyRunKindsAndAcknowledgesSuccessfulAndDuplicateDelivery() {
        TutorLedger ledger = mock(TutorLedger.class);
        TemporalRunGateway gateway = mock(TemporalRunGateway.class);
        when(ledger.outstandingEvents(RUN_KINDS, 50)).thenReturn(List.of(
                event("start-1", "RUN_START", "run-1"),
                event("start-duplicate", "RUN_START", "run-1"),
                event("index-event", "INDEX_SOURCE", "source-1"),
                new OutboxEvent("wrong-aggregate", ACCOUNT_ID, "SOURCE", "source-2", 1,
                "RUN_START", "digest", "PENDING")));
        when(ledger.execution(ACCOUNT_ID, "run-1")).thenReturn(execution("run-1", Status.QUEUED, null));
        when(ledger.getByRequestId(ACCOUNT_ID, "request-run-1")).thenReturn(request("run-1"));

        dispatcher(ledger, gateway).run();

        verify(ledger).outstandingEvents(RUN_KINDS, 50);
        verify(gateway, times(2)).start(ACCOUNT_ID, "run-1");
        verify(ledger).acknowledgeEvent("start-1");
        verify(ledger).acknowledgeEvent("start-duplicate");
        verify(ledger, never()).acknowledgeEvent("index-event");
        verify(ledger, never()).acknowledgeEvent("wrong-aggregate");
    }

    @Test
    void failedGatewayLeavesEventPendingAndDoesNotBlockLaterDelivery() {
        TutorLedger ledger = mock(TutorLedger.class);
        TemporalRunGateway gateway = mock(TemporalRunGateway.class);
        when(ledger.outstandingEvents(RUN_KINDS, 50)).thenReturn(List.of(
                event("first", "RUN_START", "run-1"), event("second", "RUN_START", "run-2")));
        when(ledger.execution(ACCOUNT_ID, "run-1")).thenReturn(execution("run-1", Status.QUEUED, null));
        when(ledger.execution(ACCOUNT_ID, "run-2")).thenReturn(execution("run-2", Status.QUEUED, null));
        when(ledger.getByRequestId(ACCOUNT_ID, "request-run-1")).thenReturn(request("run-1"));
        when(ledger.getByRequestId(ACCOUNT_ID, "request-run-2")).thenReturn(request("run-2"));
        doThrow(new IllegalStateException("secret transport detail")).when(gateway).start(ACCOUNT_ID, "run-1");

        dispatcher(ledger, gateway).run();

        verify(ledger, never()).acknowledgeEvent("first");
        verify(gateway).start(ACCOUNT_ID, "run-2");
        verify(ledger).acknowledgeEvent("second");
    }

    @Test
    void expiredCancelledAndSupersededStartsNeverReachTemporal() {
        TutorLedger ledger = mock(TutorLedger.class);
        TemporalRunGateway gateway = mock(TemporalRunGateway.class);
        when(ledger.outstandingEvents(RUN_KINDS, 50)).thenReturn(List.of(
                event("expired", "RUN_START", "run-expired"),
                event("cancelled", "RUN_START", "run-cancelled"),
                event("old", "RUN_START", "run-old")));
        when(ledger.execution(ACCOUNT_ID, "run-expired"))
                .thenReturn(execution("run-expired", Status.QUEUED, null, NOW.minusSeconds(1)));
        when(ledger.execution(ACCOUNT_ID, "run-cancelled"))
                .thenReturn(execution("run-cancelled", Status.CANCELLING, NOW));
        when(ledger.execution(ACCOUNT_ID, "run-old"))
                .thenReturn(execution("run-old", Status.QUEUED, null));
        when(ledger.getByRequestId(ACCOUNT_ID, "request-run-expired"))
                .thenReturn(request("run-expired"));
        when(ledger.getByRequestId(ACCOUNT_ID, "request-run-cancelled"))
                .thenReturn(request("run-cancelled"));
        when(ledger.getByRequestId(ACCOUNT_ID, "request-run-old"))
                .thenReturn(request("new-retry-run"));

        dispatcher(ledger, gateway).run();

        verify(ledger).reconcileDeadlines(NOW, 50);
        verify(ledger, never()).acknowledgeEvent("expired");
        verify(ledger).acknowledgeEvent("cancelled");
        verify(ledger).acknowledgeEvent("old");
        verify(gateway, never()).start(ACCOUNT_ID, "run-expired");
        verify(gateway, never()).start(ACCOUNT_ID, "run-cancelled");
        verify(gateway, never()).start(ACCOUNT_ID, "run-old");
    }

    @Test
    void lateCancelSignalsOnlyItsOldRunEvenAfterRetry() {
        TutorLedger ledger = mock(TutorLedger.class);
        TemporalRunGateway gateway = mock(TemporalRunGateway.class);
        when(ledger.outstandingEvents(RUN_KINDS, 50))
                .thenReturn(List.of(event("cancel-old", "RUN_CANCEL", "run-old")));
        when(ledger.execution(ACCOUNT_ID, "run-old"))
                .thenReturn(execution("run-old", Status.CANCELLED, NOW));
        when(ledger.getByRequestId(ACCOUNT_ID, "request-run-old"))
                .thenReturn(request("run-new"));

        dispatcher(ledger, gateway).run();

        verify(gateway).requestCancellation(ACCOUNT_ID, "run-old");
        verify(gateway, never()).requestCancellation(ACCOUNT_ID, "run-new");
        verify(ledger).acknowledgeEvent("cancel-old");
    }

    @Test
    void missingWorkflowAfterCommittedCancellationCanBeAcknowledged() {
        TutorLedger ledger = mock(TutorLedger.class);
        TemporalRunGateway gateway = mock(TemporalRunGateway.class);
        when(ledger.outstandingEvents(RUN_KINDS, 50))
                .thenReturn(List.of(event("cancel-missing", "RUN_CANCEL", "run-old")));
        when(ledger.execution(ACCOUNT_ID, "run-old"))
                .thenReturn(execution("run-old", Status.CANCELLED, NOW));
        doThrow(mock(WorkflowNotFoundException.class)).when(gateway)
                .requestCancellation(ACCOUNT_ID, "run-old");

        dispatcher(ledger, gateway).run();

        verify(ledger).acknowledgeEvent("cancel-missing");
    }

    private static WorkflowOutboxDispatcher dispatcher(TutorLedger ledger, TemporalRunGateway gateway) {
        return new WorkflowOutboxDispatcher(ledger, gateway, Clock.fixed(NOW, ZoneOffset.UTC), 50);
    }

    private static OutboxEvent event(String eventId, String kind, String runId) {
        return new OutboxEvent(eventId, ACCOUNT_ID, "RUN", runId, 1, kind, "digest", "PENDING");
    }

    private static Execution execution(String runId, Status status, Instant cancelRequestedAt) {
        return execution(runId, status, cancelRequestedAt, NOW.plusSeconds(60));
    }

    private static Execution execution(String runId, Status status, Instant cancelRequestedAt, Instant queueDeadline) {
        return new Execution(ACCOUNT_ID, requestId(runId), "1", runId, Mode.HINT, "prompt", "file", "source",
                1, status, 1, queueDeadline, NOW.plusSeconds(120), cancelRequestedAt, 0);
    }

    private static Request request(String currentRunId) {
        return new Request("request-1", "1", "prompt", Mode.HINT, null, null, "file", currentRunId,
                Status.RUNNING, 1, NOW.minusSeconds(60), NOW);
    }

    private static String requestId(String runId) {
        return "request-" + runId;
    }
}
