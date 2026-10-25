package com.betterlearning.server.agent.runtime;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import com.betterlearning.server.multiagent.MultiAgentModels.Role;
import com.betterlearning.server.multiagent.MultiAgentModels.StepInvocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StepOutput;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.multiagent.StepExecutor;

/** Bounded local wait for one paid provider call; the P1 ledger owns unknown outcomes. */
public final class ProviderCallDeadline implements AutoCloseable {
    private static final Duration MAX_MODEL_TIMEOUT = Duration.ofSeconds(120);
    private final ThreadPoolExecutor calls;
    private final Semaphore capacity;

    public ProviderCallDeadline(int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 64) {
            throw new IllegalArgumentException("maxConcurrent must be within 1..64");
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "provider-call-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        calls = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxConcurrent), threads, new ThreadPoolExecutor.AbortPolicy());
        capacity = new Semaphore(maxConcurrent);
    }

    /** The durable executor still decides whether a confirmed output is reused without invoking the action. */
    public StepExecutor bind(StepExecutor durable, Duration modelTimeout) {
        if (durable == null || modelTimeout == null || modelTimeout.compareTo(Duration.ofMillis(1)) < 0
                || modelTimeout.compareTo(MAX_MODEL_TIMEOUT) > 0) {
            throw new IllegalArgumentException("invalid provider call deadline configuration");
        }
        return (invocation, action) -> {
            if (invocation == null || invocation.role() == null || action == null) {
                throw new StepExecutionException("INVALID_STEP");
            }
            if (invocation.role() == Role.RETRIEVE) {
                return durable.execute(invocation, () -> executeAction(invocation, action, modelTimeout, null));
            }
            // Reserve local execution before the durable executor commits CALL_INTENT.
            if (calls.isShutdown() || !capacity.tryAcquire()) {
                throw new StepExecutionException("PROVIDER_CAPACITY_EXCEEDED");
            }
            AtomicBoolean submitted = new AtomicBoolean();
            try {
                return durable.execute(invocation, () -> executeAction(invocation, action, modelTimeout, submitted));
            } finally {
                // Reused CONFIRMED output and rejected DB admission never invoke the action.
                if (!submitted.get()) {
                    capacity.release();
                }
            }
        };
    }

    private StepOutput executeAction(StepInvocation invocation, StepExecutor.StepAction action,
            Duration modelTimeout, AtomicBoolean submitted) {
        if (invocation.role() == Role.RETRIEVE) {
            try {
                return action.call();
            } catch (RuntimeException exception) {
                throw new StepExecutionException("RETRIEVAL_FAILED");
            }
        }
        Future<StepOutput> future;
        try {
            FutureTask<StepOutput> task = new FutureTask<>(action::call);
            calls.execute(new ProviderTask(task));
            submitted.set(true);
            future = task;
        } catch (RejectedExecutionException exception) {
            // Only shutdown can race a reserved slot; no unbounded queue exists.
            throw new StepExecutionException("PROVIDER_CAPACITY_EXCEEDED");
        }
        try {
            return future.get(modelTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            // Interruption cannot establish whether the remote provider stopped or billed the call.
            throw new StepExecutionException("PROVIDER_TIMEOUT");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new StepExecutionException("PROVIDER_INTERRUPTED");
        } catch (ExecutionException exception) {
            // Never attach the supplier exception or its raw message to the durable ledger/Temporal history.
            throw new StepExecutionException("PROVIDER_FAILED");
        } catch (CancellationException exception) {
            throw new StepExecutionException("PROVIDER_INTERRUPTED");
        }
    }

    private final class ProviderTask implements Runnable {
        private final FutureTask<StepOutput> task;
        private final AtomicBoolean released = new AtomicBoolean();

        private ProviderTask(FutureTask<StepOutput> task) {
            this.task = task;
        }

        @Override
        public void run() {
            try {
                task.run();
            } finally {
                // A cancelled Future does not prove an executing provider action has ended.
                release();
            }
        }

        private void cancelQueued() {
            task.cancel(false);
            release();
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                capacity.release();
            }
        }
    }

    @Override
    public void close() {
        // shutdownNow interrupts local threads; it does not prove remote cancellation or lease release.
        for (Runnable queued : calls.shutdownNow()) {
            ((ProviderTask) queued).cancelQueued();
        }
    }
}
