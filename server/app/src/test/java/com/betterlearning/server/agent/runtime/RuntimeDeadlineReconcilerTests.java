package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.TutorLedger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RuntimeDeadlineReconcilerTests {
    @Test
    void usesDatabaseReconciliationWithoutAnyTemporalDependencyAndRetriesNextTick() {
        TutorLedger ledger = mock(TutorLedger.class);
        Instant now = Instant.parse("2026-10-25T03:00:00Z");
        doThrow(new IllegalStateException("unavailable")).doReturn(1)
                .when(ledger).reconcileDeadlines(now, 50);
        RuntimeDeadlineReconciler reconciler = new RuntimeDeadlineReconciler(ledger,
                Clock.fixed(now, ZoneOffset.UTC), 50);
        reconciler.run();
        reconciler.run();
        verify(ledger, times(2)).reconcileDeadlines(now, 50);
    }
}
