package com.betterlearning.server.agent.runtime;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/** Separate schedulers prevent a blocked Temporal RPC from stopping database deadline reconciliation. */
public final class RuntimeDispatchLoop implements SmartLifecycle {
    private final Runnable dispatch;
    private final Runnable reconcile;
    private final WorkflowRuntimeProperties properties;
    private ScheduledExecutorService dispatchScheduler;
    private ScheduledExecutorService deadlineScheduler;
    private volatile boolean running;

    public RuntimeDispatchLoop(Runnable dispatch, Runnable reconcile, WorkflowRuntimeProperties properties) {
        this.dispatch = dispatch;
        this.reconcile = reconcile;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        dispatchScheduler = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "agent-outbox"));
        deadlineScheduler = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "agent-deadlines"));
        dispatchScheduler.scheduleWithFixedDelay(dispatch, 0, properties.getDispatchInterval().toMillis(), TimeUnit.MILLISECONDS);
        deadlineScheduler.scheduleWithFixedDelay(reconcile, 0, properties.getReconciliationInterval().toMillis(), TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (dispatchScheduler != null) {
            dispatchScheduler.shutdownNow();
        }
        if (deadlineScheduler != null) {
            deadlineScheduler.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
