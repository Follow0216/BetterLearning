package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.Request;
import com.betterlearning.server.multiagent.MultiAgentModels.ResultDraft;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.betterlearning.server.multiagent.TutorLedger;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真 PostgreSQL 双连接事务竞争：同一 run 的取消与发布仅允许一种终态。 */
@Tag("integration")
@Timeout(180)
class RuntimePublishCancelRaceIntegrationTests {
    private static final int ROUNDS = 4;

    @Test
    void cancelAndPublishFromIndependentTransactionsHaveOneConsistentWinner() throws Exception {
        try (RuntimeBusinessFixture business = RuntimeBusinessFixture.start()) {
            ExecutorService contenders = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < ROUNDS; round++) {
                    raceOnce(business, contenders, round);
                }
            } finally {
                contenders.shutdownNow();
                assertTrue(contenders.awaitTermination(10, TimeUnit.SECONDS), "contest threads did not stop");
            }
        }
    }

    private void raceOnce(RuntimeBusinessFixture business, ExecutorService contenders, int round) throws Exception {
        RuntimeBusinessFixture.Seed seed = business.createQueuedRun();
        TutorLedger ledger = business.ledger();
        long accountId = seed.accountId();
        String runId = seed.request().currentRunId();
        Execution queued = ledger.execution(accountId, runId);
        Execution running = ledger.start(accountId, runId, queued.revision()).orElseThrow();
        assertEquals(Status.RUNNING, running.status());

        CountDownLatch bothInTransaction = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);
        PlatformTransactionManager manager = business.context().getBean(PlatformTransactionManager.class);
        Future<ContestResult<Request>> cancellation = contenders.submit(() -> contend(manager, business.jdbc(),
                bothInTransaction, proceed,
                () -> ledger.cancel(accountId, seed.questionId(), seed.request().requestId(), runId)));
        Future<ContestResult<Boolean>> publication = contenders.submit(() -> contend(manager, business.jdbc(),
                bothInTransaction, proceed,
                () -> ledger.publish(accountId, runId, running.revision(),
                    new ResultDraft("已核验的提示", List.of()))));
        try {
            assertTrue(bothInTransaction.await(15, TimeUnit.SECONDS),
                    "round " + round + ": both PostgreSQL transactions must reach the gate");
            proceed.countDown();
            ContestResult<Request> cancelled = cancellation.get(30, TimeUnit.SECONDS);
            ContestResult<Boolean> published = publication.get(30, TimeUnit.SECONDS);
            assertNotEquals(cancelled.before().backendPid(), published.before().backendPid());
            assertNotEquals(cancelled.before().transactionId(), published.before().transactionId());
            assertEquals(cancelled.before(), cancelled.after(), "cancel must stay on one PG transaction");
            assertEquals(published.before(), published.after(), "publish must stay on one PG transaction");

            int results = resultCount(business.jdbc(), runId);
            Status finalStatus = ledger.execution(accountId, runId).status();
            if (published.value()) {
                assertEquals(Status.SUCCEEDED, cancelled.value().status());
                assertEquals(Status.SUCCEEDED, finalStatus);
                assertEquals(1, results);
            } else {
                assertEquals(Status.CANCELLING, cancelled.value().status());
                assertEquals(Status.CANCELLING, finalStatus);
                assertEquals(0, results);
                ledger.reconcileDeadlines(Instant.now().plusSeconds(31), 100);
                assertEquals(Status.CANCELLED, ledger.execution(accountId, runId).status());
                assertEquals(0, resultCount(business.jdbc(), runId));
                assertFalse(ledger.publish(accountId, runId, running.revision(),
                        new ResultDraft("迟到的提示", List.of())));
            }
            System.out.printf("P2_CANCEL_PUBLISH_RACE round=%d run=%s cancelBackend=%d cancelTx=%d "
                    + "publishBackend=%d publishTx=%d winner=%s terminal=%s results=%d%n", round, runId,
                    cancelled.before().backendPid(), cancelled.before().transactionId(),
                    published.before().backendPid(), published.before().transactionId(),
                    published.value() ? "PUBLISH" : "CANCEL", ledger.execution(accountId, runId).status(),
                    resultCount(business.jdbc(), runId));
        } finally {
            proceed.countDown();
        }
    }

    private <T> ContestResult<T> contend(PlatformTransactionManager manager, JdbcTemplate jdbc,
            CountDownLatch bothInTransaction, CountDownLatch proceed, Supplier<T> action) {
        return Objects.requireNonNull(new TransactionTemplate(manager).execute(ignored -> {
            TxIdentity before = identity(jdbc);
            bothInTransaction.countDown();
            awaitRelease(proceed);
            T value = action.get();
            TxIdentity after = identity(jdbc);
            return new ContestResult<>(before, after, value);
        }));
    }

    private TxIdentity identity(JdbcTemplate jdbc) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid(), txid_current()",
                (row, ignored) -> new TxIdentity(row.getInt(1), row.getLong(2))));
    }

    private int resultCount(JdbcTemplate jdbc, String runId) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM ma_result WHERE run_id = ?",
                Integer.class, runId));
    }

    private void awaitRelease(CountDownLatch proceed) {
        try {
            if (!proceed.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("PostgreSQL contest gate timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PostgreSQL contest gate interrupted", error);
        }
    }

    private record TxIdentity(int backendPid, long transactionId) { }

    private record ContestResult<T>(TxIdentity before, TxIdentity after, T value) { }
}
