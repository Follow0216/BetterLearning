package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.OutboxEvent;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.betterlearning.server.multiagent.TutorLedger;
import io.temporal.client.WorkflowNotFoundException;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** At-least-once transport; immutable workflow IDs and the business ledger supply idempotency. */
public final class WorkflowOutboxDispatcher implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowOutboxDispatcher.class);
    private final TutorLedger ledger;
    private final TemporalRunGateway gateway;
    private final Clock clock;
    private final int batchSize;

    public WorkflowOutboxDispatcher(TutorLedger ledger, TemporalRunGateway gateway, Clock clock, int batchSize) {
        this.ledger = ledger;
        this.gateway = gateway;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    public void run() {
        try {
            for (OutboxEvent event : ledger.outstandingEvents(Set.of("RUN_START", "RUN_CANCEL"), batchSize)) {
                if (!"RUN".equals(event.aggregateType())
                        || !("RUN_START".equals(event.kind()) || "RUN_CANCEL".equals(event.kind()))) {
                    continue;
                }
                deliver(event);
            }
        } catch (RuntimeException unavailableLedger) {
            LOG.warn("Agent outbox polling deferred: LEDGER_UNAVAILABLE");
        }
    }

    private void deliver(OutboxEvent event) {
        try {
            Execution execution = ledger.execution(event.accountId(), event.aggregateId());
            if (execution.accountId() != event.accountId() || !execution.runId().equals(event.aggregateId())) {
                throw new IllegalStateException("outbox identity mismatch");
            }
            if ("RUN_CANCEL".equals(event.kind())) {
                cancel(execution);
            } else if (!start(execution)) {
                return;
            }
            ledger.acknowledgeEvent(event.eventId());
        } catch (RuntimeException deliveryFailed) {
            LOG.warn("Agent outbox delivery deferred: TRANSPORT_OR_LEDGER_UNAVAILABLE");
        }
    }

    private boolean start(Execution execution) {
        String currentRun = ledger.getByRequestId(execution.accountId(), execution.requestId()).currentRunId();
        if (!execution.runId().equals(currentRun) || execution.cancelRequestedAt() != null
                || (execution.status() != Status.QUEUED && execution.status() != Status.RUNNING)) {
            return true;
        }
        if (!execution.runDeadlineAt().isAfter(clock.instant())
                || (execution.status() == Status.QUEUED && !execution.queueDeadlineAt().isAfter(clock.instant()))) {
            ledger.reconcileDeadlines(clock.instant(), batchSize);
            return false;
        }
        gateway.start(execution.accountId(), execution.runId());
        return true;
    }

    private void cancel(Execution execution) {
        if (execution.cancelRequestedAt() == null) {
            return;
        }
        try {
            gateway.requestCancellation(execution.accountId(), execution.runId());
        } catch (WorkflowNotFoundException notStarted) {
            // The committed DB cancellation blocks begin/callIntent/publish, including a late start RPC.
        }
    }
}
