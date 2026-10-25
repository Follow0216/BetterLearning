package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.MultiAgentModels.Attempt;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import io.temporal.api.enums.v1.EventType;
import io.temporal.testing.WorkflowReplayer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual child JVM kill/restart, PostgreSQL ledger and persistent Temporal Server; provider is a counting stub. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class TemporalRuntimeFaultIntegrationTests {
    private PersistentTemporalFixture temporal;

    @BeforeAll
    void startTemporal() {
        temporal = PersistentTemporalFixture.start();
    }

    @AfterAll
    void stopTemporal() {
        if (temporal != null) {
            temporal.close();
        }
    }

    @Test
    void committedOutboxSurvivesDispatcherProcessDeathBeforeStart() throws Exception {
        try (RuntimeFaultScenario scenario = new RuntimeFaultScenario(temporal)) {
            assertTrue(scenario.business().context().getBeansOfType(
                    com.betterlearning.server.agent.TutorDeadlineReconciler.class).isEmpty(),
                    "the non-HTTP worker must not install the API's scheduled reconciliation loop");
            Process beforeStart = scenario.startDispatcher(false);
            scenario.kill(beforeStart);
            assertEquals(Status.QUEUED, execution(scenario).status());
            assertEquals(0, scenario.totalCalls());

            scenario.startWorker("NONE");
            Process replacement = scenario.startDispatcher(true);
            assertTrue(replacement.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, replacement.exitValue());
            scenario.awaitStatus(Status.SUCCEEDED);
            scenario.awaitWorkflowCompletion();
            // Transport acknowledgement loss/repeated delivery cannot create a new execution.
            scenario.gateway().start(scenario.seed().accountId(), run(scenario));
            scenario.dispatch();
            assertEquals(4, scenario.totalCalls());
            assertEquals(1, resultCount(scenario));
            long starts = scenario.client().fetchHistory(run(scenario)).getEvents().stream()
                    .filter(event -> event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED).count();
            assertEquals(1, starts);
            scenario.writeEvidence("committed-outbox-dispatcher-kill");
        }
    }

    @Test
    void confirmedRoleSurvivesWorkerKillAndPostgresBackedTemporalRestart() throws Exception {
        try (RuntimeFaultScenario scenario = new RuntimeFaultScenario(temporal)) {
            scenario.startWorker("AFTER_CONFIRM");
            scenario.dispatch();
            scenario.awaitBarrier("confirmed");
            assertEquals("CONFIRMED", firstAttempt(scenario).providerCallState());
            assertEquals(1, scenario.calls("SOLVE"));
            long firstPid = scenario.workerPid();
            scenario.killWorker();
            scenario.releaseBarriers();

            temporal.restartServer();
            scenario.refreshTemporalConnection();
            scenario.startWorker("NONE");
            assertNotEquals(firstPid, scenario.workerPid());
            scenario.awaitStatus(Status.SUCCEEDED);
            scenario.awaitWorkflowCompletion();
            assertEquals(1, scenario.calls("SOLVE"), "the confirmed role must be reused from PostgreSQL");
            assertEquals(4, scenario.totalCalls());
            var history = scenario.client().fetchHistory(run(scenario));
            WorkflowReplayer.replayWorkflowExecution(history, TutorRunWorkflowImpl.class);
            assertEquals(4, scenario.totalCalls(), "history replay must not invoke the provider");
            assertFalse(history.toJson(true).contains("SOLVE-output"));
            scenario.writeEvidence("confirmed-role-worker-and-temporal-restart");
        }
    }

    @Test
    void publishedResultSurvivesLostActivityAcknowledgement() throws Exception {
        try (RuntimeFaultScenario scenario = new RuntimeFaultScenario(temporal)) {
            scenario.startWorker("AFTER_PUBLISH");
            scenario.dispatch();
            scenario.awaitBarrier("published");
            assertEquals(Status.SUCCEEDED, execution(scenario).status());
            assertEquals(1, resultCount(scenario));
            scenario.killWorker();
            scenario.releaseBarriers();
            scenario.startWorker("NONE");
            scenario.awaitWorkflowCompletion();
            assertEquals(4, scenario.totalCalls());
            assertEquals(1, resultCount(scenario));
            assertEquals(Status.SUCCEEDED, execution(scenario).status());
            scenario.writeEvidence("published-result-ack-loss");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_RESPONSE", "AFTER_RESPONSE"})
    void workerDeathWithUnconfirmedProviderOutcomeNeverResends(String window) throws Exception {
        try (RuntimeFaultScenario scenario = new RuntimeFaultScenario(temporal)) {
            if ("BEFORE_RESPONSE".equals(window)) {
                scenario.blockCalls();
            }
            scenario.startWorker("AFTER_RESPONSE".equals(window) ? "AFTER_RESPONSE" : "NONE");
            scenario.dispatch();
            if ("BEFORE_RESPONSE".equals(window)) {
                scenario.awaitCall();
            } else {
                scenario.awaitBarrier("response");
            }
            Attempt sent = firstAttempt(scenario);
            assertEquals("CALL_INTENT", sent.providerCallState());
            scenario.killWorker();
            scenario.releaseCalls();
            scenario.releaseBarriers();
            scenario.startWorker("NONE");
            scenario.awaitStatus(Status.FAILED);
            scenario.awaitWorkflowCompletion();
            assertEquals("OUTCOME_UNKNOWN", scenario.business().ledger().detail(scenario.seed().accountId(),
                    scenario.seed().questionId(), scenario.seed().request().requestId()).run().reason());
            assertEquals(1, scenario.totalCalls());
            assertEquals(0, resultCount(scenario));
            assertFalse(scenario.business().ledger().confirm(scenario.seed().accountId(), run(scenario),
                    sent.attemptId(), sent.fence(), "late", digest("late")));
            scenario.writeEvidence("unknown-outcome-" + window.toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    void cancellationKeepsSentCallBudgetAndRejectsLateResult() throws Exception {
        try (RuntimeFaultScenario scenario = new RuntimeFaultScenario(temporal)) {
            scenario.blockCalls();
            scenario.startWorker("NONE");
            scenario.dispatch();
            scenario.awaitCall();
            var ledger = scenario.business().ledger();
            ledger.cancel(scenario.seed().accountId(), scenario.seed().questionId(),
                    scenario.seed().request().requestId(), run(scenario));
            scenario.dispatch();
            scenario.awaitWorkflowCompletion();
            assertEquals(1, ledger.providerCallsInFlight(scenario.seed().accountId()));
            assertEquals(0, resultCount(scenario));
            scenario.releaseCalls();
            await(() -> ledger.providerCallsInFlight(scenario.seed().accountId()) == 0, 20);
            assertEquals(1, scenario.totalCalls());
            assertEquals(0, resultCount(scenario));
            scenario.business().jdbc().update("UPDATE ma_run SET cancel_requested_at = "
                    + "CURRENT_TIMESTAMP - INTERVAL '31 seconds' WHERE run_id = ?", run(scenario));
            new RuntimeDeadlineReconciler(ledger, Clock.systemUTC(), 100).run();
            assertEquals(Status.CANCELLED, execution(scenario).status());
            scenario.writeEvidence("cancel-late-response");
        }
    }

    @Test
    void independentProviderDeadlineReconciliationStopsLatePublish() throws Exception {
        try (RuntimeFaultScenario scenario = new RuntimeFaultScenario(temporal)) {
            scenario.blockCalls();
            scenario.startWorker("NONE");
            scenario.dispatch();
            scenario.awaitCall();
            Attempt sent = firstAttempt(scenario);
            scenario.business().jdbc().update("UPDATE ma_attempt SET provider_deadline_at = "
                    + "CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE attempt_id = ?", sent.attemptId());
            new RuntimeDeadlineReconciler(scenario.business().ledger(), Clock.systemUTC(), 100).run();
            assertEquals(Status.FAILED, execution(scenario).status());
            assertEquals("UNKNOWN", firstAttempt(scenario).providerCallState());
            scenario.releaseCalls();
            scenario.awaitWorkflowCompletion();
            assertEquals(1, scenario.totalCalls());
            assertEquals(0, resultCount(scenario));
            scenario.writeEvidence("provider-deadline-late-response");
        }
    }

    @Test
    void timedOutRunCannotPublishIntoExplicitRetryWhileOldCallStillOwnsBudget() throws Exception {
        try (RuntimeFaultScenario scenario = new RuntimeFaultScenario(temporal)) {
            scenario.blockCalls();
            scenario.startWorker("NONE");
            scenario.dispatch();
            scenario.awaitCall();
            Attempt oldAttempt = firstAttempt(scenario);
            var ledger = scenario.business().ledger();
            scenario.business().jdbc().update("UPDATE ma_run SET run_deadline_at = "
                    + "CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE run_id = ?", run(scenario));
            new RuntimeDeadlineReconciler(ledger, Clock.systemUTC(), 100).run();
            // A run deadline with a sent, unconfirmed call must retain the stronger unknown-outcome state.
            assertEquals(Status.FAILED, execution(scenario).status());
            assertEquals("OUTCOME_UNKNOWN", ledger.detail(scenario.seed().accountId(),
                    scenario.seed().questionId(), scenario.seed().request().requestId()).run().reason());
            var retry = ledger.retry(scenario.seed().accountId(), scenario.seed().questionId(),
                    scenario.seed().request().requestId(), java.util.UUID.randomUUID().toString(), run(scenario));
            assertNotEquals(run(scenario), retry.currentRunId());
            scenario.dispatch();
            await(() -> !ledger.detail(scenario.seed().accountId(), scenario.seed().questionId(),
                    retry.requestId()).run().attempts().isEmpty(), 20);
            assertEquals(1, ledger.providerCallsInFlight(scenario.seed().accountId()));
            assertEquals(1, scenario.totalCalls(), "retry must wait for the old provider execution lease");
            assertEquals("NOT_SENT", ledger.detail(scenario.seed().accountId(), scenario.seed().questionId(),
                    retry.requestId()).run().attempts().get(0).providerCallState());
            scenario.releaseCalls();
            await(() -> ledger.execution(scenario.seed().accountId(), retry.currentRunId()).status()
                    == Status.SUCCEEDED, 45);
            scenario.awaitWorkflowCompletion();
            scenario.awaitWorkflowCompletion(retry.currentRunId());
            assertEquals(Status.FAILED, execution(scenario).status());
            assertEquals(0, resultCount(scenario));
            assertEquals(5, scenario.totalCalls());
            assertEquals(1, scenario.business().jdbc().queryForObject(
                    "SELECT COUNT(*) FROM ma_result WHERE run_id = ?", Integer.class, retry.currentRunId()));
            assertFalse(ledger.confirm(scenario.seed().accountId(), run(scenario), oldAttempt.attemptId(),
                    oldAttempt.fence(), "late", digest("late")));
            scenario.writeEvidence("run-timeout-explicit-retry");
            scenario.writeEvidence("retry-after-old-run-timeout", retry.currentRunId());
        }
    }

    private com.betterlearning.server.multiagent.MultiAgentModels.Execution execution(RuntimeFaultScenario scenario) {
        return scenario.business().ledger().execution(scenario.seed().accountId(), run(scenario));
    }

    private Attempt firstAttempt(RuntimeFaultScenario scenario) {
        return scenario.business().ledger().detail(scenario.seed().accountId(), scenario.seed().questionId(),
                scenario.seed().request().requestId()).run().attempts().get(0);
    }

    private int resultCount(RuntimeFaultScenario scenario) {
        return scenario.business().jdbc().queryForObject("SELECT COUNT(*) FROM ma_result WHERE run_id = ?",
                Integer.class, run(scenario));
    }

    private String run(RuntimeFaultScenario scenario) {
        return scenario.seed().request().currentRunId();
    }

    private String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void await(BooleanSupplier predicate, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!predicate.getAsBoolean()) {
            if (System.nanoTime() >= end) {
                throw new AssertionError("business condition did not converge");
            }
            Thread.sleep(50);
        }
    }
}
