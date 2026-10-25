package com.betterlearning.server.agent.runtime;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDispatchLoopTests {
    @Test
    void blockedDispatchDoesNotDelayDeadlineReconciliation() throws Exception {
        CountDownLatch dispatchEntered = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        CountDownLatch reconciled = new CountDownLatch(1);
        Runnable dispatch = () -> {
            dispatchEntered.countDown();
            try {
                releaseDispatch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setDispatchInterval(Duration.ofMillis(10));
        properties.setReconciliationInterval(Duration.ofMillis(10));
        RuntimeDispatchLoop loop = new RuntimeDispatchLoop(dispatch, reconciled::countDown, properties);

        try {
            loop.start();
            assertTrue(dispatchEntered.await(1, TimeUnit.SECONDS));
            assertTrue(reconciled.await(1, TimeUnit.SECONDS),
                    "database deadline tick must run while dispatch is blocked");
            assertTrue(loop.isRunning());
        } finally {
            releaseDispatch.countDown();
            loop.stop();
        }
        assertFalse(loop.isRunning());
    }
}
