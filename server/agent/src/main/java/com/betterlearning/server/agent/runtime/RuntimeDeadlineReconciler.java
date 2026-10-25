package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.TutorLedger;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs without a Temporal client, including while dispatch or the Temporal service is unavailable. */
public final class RuntimeDeadlineReconciler implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(RuntimeDeadlineReconciler.class);
    private final TutorLedger ledger;
    private final Clock clock;
    private final int batchSize;

    public RuntimeDeadlineReconciler(TutorLedger ledger, Clock clock, int batchSize) {
        this.ledger = ledger;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    public void run() {
        try {
            ledger.reconcileDeadlines(clock.instant(), batchSize);
        } catch (RuntimeException unavailableLedger) {
            // Do not serialize SQL/provider exception messages into application logs.
            LOG.warn("Agent deadline reconciliation deferred: LEDGER_UNAVAILABLE");
        }
    }
}
