package com.betterlearning.server.agent.runtime;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** In-process protocol tests; these do not prove durable Temporal Server recovery. */
class TutorRunWorkflowTests {
    private static final String QUEUE = "tutor-workflow-unit";
    private static final long ACCOUNT_ID = 42L;

    @Test
    void runsFiniteStepsAndReplaysWithoutRepeatingActivities() throws Exception {
        LedgerActivities ledger = new LedgerActivities(List.of("solve", "diagnose", "teach", "review"));
        ModelActivities model = new ModelActivities(false);
        try (Harness harness = new Harness(ledger, model)) {
            WorkflowExecution execution = harness.start();
            harness.awaitResult();

            assertEquals(List.of("solve", "diagnose", "teach", "review"), model.executed);
            assertEquals(1, ledger.beginCalls.get());
            assertEquals(5, ledger.nextCalls.get());
            assertEquals(0, ledger.recoverCalls.get());
            assertEquals(0, ledger.settleCalls.get());
            assertEquals(List.of(ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID),
                    ledger.accounts);
            assertEquals(List.of(ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID), model.accounts);

            WorkflowReplayer.replayWorkflowExecution(
                    harness.environment.getWorkflowExecutionHistory(execution), TutorRunWorkflowImpl.class);
            assertEquals(4, model.calls.get(), "history replay must not repeat paid Activities");
            assertEquals(1, ledger.beginCalls.get(), "history replay must not repeat ledger Activities");
        }
    }

    @Test
    void failedActivityIsAttemptedOnceAndStopsWhenLedgerCannotRecover() {
        LedgerActivities ledger = new LedgerActivities(List.of("solve", "teach"));
        ledger.recover = false;
        ModelActivities model = new ModelActivities(true);
        try (Harness harness = new Harness(ledger, model)) {
            harness.start();
            harness.awaitResult();
            assertEquals(1, model.calls.get());
            assertEquals(List.of("solve"), model.executed);
            assertEquals(1, ledger.recoverCalls.get());
            assertEquals(1, ledger.nextCalls.get(), "no later step may be scheduled");
            assertEquals(List.of(ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID), ledger.accounts);
            assertEquals(List.of(ACCOUNT_ID), model.accounts);
        }
    }

    @Test
    void lostActivityAcknowledgementRecoversPersistedOutputWithoutReexecution() {
        LedgerActivities ledger = new LedgerActivities(List.of("solve", "teach"));
        ledger.recover = true;
        ModelActivities model = new ModelActivities(true);
        try (Harness harness = new Harness(ledger, model)) {
            harness.start();
            harness.awaitResult();
            assertEquals(List.of("solve", "teach"), model.executed);
            assertEquals(2, model.calls.get(), "failed solve must not be called again");
            assertEquals(1, ledger.recoverCalls.get());
            assertEquals(3, ledger.nextCalls.get());
            assertEquals(List.of(ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID), ledger.accounts);
            assertEquals(List.of(ACCOUNT_ID, ACCOUNT_ID), model.accounts);
        }
    }

    @Test
    void expiredBeginDoesNotScheduleAChargeableStep() {
        LedgerActivities ledger = new LedgerActivities(List.of("solve"));
        ledger.deadlineOffsetMillis = -Duration.ofHours(1).toMillis();
        ModelActivities model = new ModelActivities(false);
        try (Harness harness = new Harness(ledger, model)) {
            harness.start();
            harness.awaitResult();
            assertEquals(0, model.calls.get());
            assertEquals(0, ledger.nextCalls.get());
            assertEquals(List.of("TIMED_OUT"), ledger.settlements);
            assertEquals(List.of(ACCOUNT_ID, ACCOUNT_ID), ledger.accounts);
        }
    }

    @Test
    void cancellationWhileResolvingNextStepDoesNotExecuteThatStep() throws Exception {
        LedgerActivities ledger = new LedgerActivities(List.of("solve", "teach"));
        ledger.blockOrdinal = 1;
        ModelActivities model = new ModelActivities(false);
        try (Harness harness = new Harness(ledger, model)) {
            harness.start();
            assertTrue(ledger.nextStepEntered.await(5, TimeUnit.SECONDS));
            harness.workflow.requestCancellation();
            ledger.releaseNextStep.countDown();
            harness.awaitResult();
            assertEquals(List.of("solve"), model.executed);
            assertEquals(List.of("CANCELLED"), ledger.settlements);
            assertEquals(List.of(ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID), ledger.accounts);
            assertEquals(List.of(ACCOUNT_ID), model.accounts);
        } finally {
            ledger.releaseNextStep.countDown();
        }
    }

    @Test
    void invalidAccountFailsBeforeAnyActivity() {
        LedgerActivities ledger = new LedgerActivities(List.of("solve"));
        ModelActivities model = new ModelActivities(false);
        try (Harness harness = new Harness(ledger, model)) {
            harness.start(0);
            assertThrows(WorkflowFailedException.class, harness::awaitResult);
            assertEquals(0, ledger.beginCalls.get());
            assertEquals(0, model.calls.get());
        }
    }

    private static final class Harness implements AutoCloseable {
        private final TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        private final TutorRunWorkflow workflow;
        private final String runId = UUID.randomUUID().toString();

        private Harness(LedgerActivities ledger, ModelActivities model) {
            Worker worker = environment.newWorker(QUEUE);
            worker.registerWorkflowImplementationTypes(TutorRunWorkflowImpl.class);
            worker.registerActivitiesImplementations(ledger, model);
            environment.start();
            workflow = environment.getWorkflowClient().newWorkflowStub(TutorRunWorkflow.class,
                    TemporalExecutionPolicy.workflow(runId, QUEUE));
        }

        private WorkflowExecution start() {
            return start(ACCOUNT_ID);
        }

        private WorkflowExecution start(long accountId) {
            return WorkflowClient.start(workflow::execute, accountId, runId, 5_000L, 10_000L);
        }

        private void awaitResult() {
            try {
                WorkflowStub.fromTyped(workflow).getResult(15, TimeUnit.SECONDS, Void.class);
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw new AssertionError("workflow did not finish within the test deadline", timeout);
            }
        }

        @Override
        public void close() {
            environment.close();
        }
    }

    private static final class LedgerActivities implements RunLifecycleActivities {
        private final List<String> steps;
        private final AtomicInteger beginCalls = new AtomicInteger();
        private final AtomicInteger nextCalls = new AtomicInteger();
        private final AtomicInteger recoverCalls = new AtomicInteger();
        private final AtomicInteger settleCalls = new AtomicInteger();
        private final java.util.List<String> settlements = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<Long> accounts = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final CountDownLatch nextStepEntered = new CountDownLatch(1);
        private final CountDownLatch releaseNextStep = new CountDownLatch(1);
        private volatile long deadlineOffsetMillis = Duration.ofMinutes(1).toMillis();
        private volatile int blockOrdinal = -1;
        private volatile boolean recover;

        private LedgerActivities(List<String> steps) {
            this.steps = steps;
        }

        @Override
        public long begin(long accountId, String runId) {
            accounts.add(accountId);
            beginCalls.incrementAndGet();
            return System.currentTimeMillis() + deadlineOffsetMillis;
        }

        @Override
        public String nextStep(long accountId, String runId, int ordinal) {
            accounts.add(accountId);
            nextCalls.incrementAndGet();
            if (ordinal == blockOrdinal) {
                nextStepEntered.countDown();
                try {
                    if (!releaseNextStep.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release nextStep");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted", exception);
                }
            }
            return ordinal < steps.size() ? steps.get(ordinal) : null;
        }

        @Override
        public boolean recoverStep(long accountId, String runId, String stepId) {
            accounts.add(accountId);
            recoverCalls.incrementAndGet();
            return recover;
        }

        @Override
        public void settle(long accountId, String runId, String reason) {
            accounts.add(accountId);
            settleCalls.incrementAndGet();
            settlements.add(reason);
        }
    }

    private static final class ModelActivities implements RunStepActivities {
        private final AtomicInteger calls = new AtomicInteger();
        private final java.util.List<String> executed = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<Long> accounts = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final boolean failSolve;

        private ModelActivities(boolean failSolve) {
            this.failSolve = failSolve;
        }

        @Override
        public void executeStep(long accountId, String runId, String stepId, long modelTimeoutMillis) {
            accounts.add(accountId);
            calls.incrementAndGet();
            executed.add(stepId);
            if (failSolve && "solve".equals(stepId)) {
                throw new IllegalStateException("simulated Activity acknowledgement failure");
            }
        }
    }
}
