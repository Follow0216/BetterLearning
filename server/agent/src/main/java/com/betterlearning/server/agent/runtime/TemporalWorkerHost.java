package com.betterlearning.server.agent.runtime;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/** One worker factory per process, with bounded shutdown and no HTTP lifecycle dependency. */
public final class TemporalWorkerHost implements SmartLifecycle {
    private final WorkerFactory factory;
    private final WorkflowRuntimeProperties properties;
    private volatile boolean running;

    public TemporalWorkerHost(WorkflowClient client, WorkflowRuntimeProperties properties,
            RunLifecycleActivities lifecycle, RunStepActivities steps) {
        this.properties = properties;
        factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(properties.getTaskQueue(), WorkerOptions.newBuilder()
                .setMaxConcurrentActivityExecutionSize(properties.getMaxConcurrentActivities())
                .build());
        worker.registerWorkflowImplementationTypes(TutorRunWorkflowImpl.class);
        worker.registerActivitiesImplementations(lifecycle, steps);
    }

    @Override
    public void start() {
        factory.start();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        factory.shutdown();
        factory.awaitTermination(properties.getShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!factory.isTerminated()) {
            factory.shutdownNow();
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
