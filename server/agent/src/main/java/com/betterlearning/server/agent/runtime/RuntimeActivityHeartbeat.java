package com.betterlearning.server.agent.runtime;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Detects a killed worker without putting provider/source data in heartbeat details. */
final class RuntimeActivityHeartbeat {
    private RuntimeActivityHeartbeat() {
    }

    static Runnable start() {
        ActivityExecutionContext context = Activity.getExecutionContext();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "agent-activity-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                context.heartbeat("running");
            } catch (RuntimeException cancelledOrClosed) {
                scheduler.shutdown();
            }
        }, 0, 1, TimeUnit.SECONDS);
        return scheduler::shutdownNow;
    }
}
