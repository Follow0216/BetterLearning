package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.betterlearning.server.ServerApplication;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.service.TutorTurnService;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.Attempt;
import com.betterlearning.server.multiagent.MultiAgentModels.CreateRequest;
import com.betterlearning.server.multiagent.MultiAgentModels.Evidence;
import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.Mode;
import com.betterlearning.server.multiagent.MultiAgentModels.PublishSource;
import com.betterlearning.server.multiagent.MultiAgentModels.Request;
import com.betterlearning.server.multiagent.MultiAgentModels.RequestPage;
import com.betterlearning.server.multiagent.MultiAgentModels.ResultDraft;
import com.betterlearning.server.multiagent.MultiAgentModels.Role;
import com.betterlearning.server.multiagent.MultiAgentModels.SnapshotBinding;
import com.betterlearning.server.multiagent.MultiAgentModels.SnapshotInput;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.TutorLedger;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;

import jakarta.servlet.http.Part;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(classes = ServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class TutorLedgerIntegrationTests {
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-ledger-integration-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "ledger-test", "ledger-client");
        IntegrationProperties.registerRateLimits(registry);
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
        registry.add("app.files.root", () -> System.getProperty("java.io.tmpdir"));
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TutorLedger ledger;

    @Autowired
    private TutorTurnService legacy;

    @Autowired
    private SourceStore sources;

    @Autowired
    private AnswerFileStore files;

    @Autowired
    private JsonMapper json;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, redis);
    }

    @Test
    void requestIdempotencyRejectsChangedInputsAndScopesEveryReadToAccount() {
        long alice = account();
        long bob = account();
        String question = question(alice);
        String otherQuestion = question(alice);
        String bobQuestion = question(bob);
        String requestId = id();
        CreateRequest command = new CreateRequest(requestId, "提示第一步", Mode.HINT, null);
        Request first = ledger.create(alice, question, requestId, command);
        assertThat(first.status()).isEqualTo(Status.WAITING_INPUT);
        assertThat(first.currentRunId()).isNull();
        assertThat(ledger.create(alice, question, requestId,
                new CreateRequest(requestId, "提示第一步", Mode.HINT,
                        new SnapshotInput("NEW_CAPTURE", null))).requestId()).isEqualTo(requestId);
        assertConflict(() -> ledger.create(alice, question, requestId,
                new CreateRequest(requestId, "换个问题", Mode.HINT, null)));
        assertConflict(() -> ledger.create(alice, question, requestId,
                new CreateRequest(requestId, "提示第一步", Mode.FULL_SOLUTION, null)));
        assertConflict(() -> ledger.create(alice, otherQuestion, requestId, command));
        assertThatThrownBy(() -> ledger.get(bob, question, requestId)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ledger.getByRequestId(bob, requestId)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ledger.bindSnapshot(bob, question, requestId, binding(alice, requestId)))
                .isInstanceOf(BusinessException.class);
        assertThat(ledger.pending(alice, question).request().requestId()).isEqualTo(requestId);
        assertThat(ledger.pending(bob, bobQuestion).request()).isNull();
    }

    @Test
    void reuseChainPinsOriginalSnapshotAndRejectsCrossAccountOrLiveSource() {
        long alice = account();
        long bob = account();
        String question = question(alice);
        String otherQuestion = question(alice);
        String firstId = id();
        ledger.create(alice, question, firstId, create(firstId));
        SnapshotBinding frozen = binding(alice, firstId);
        Request first = ledger.bindSnapshot(alice, question, firstId, frozen);
        assertThat(first.status()).isEqualTo(Status.QUEUED);
        assertThat(first.snapshotFileRef()).isEqualTo(frozen.fileRef());
        String tooEarly = id();
        assertConflict(() -> ledger.create(alice, question, tooEarly,
                reuse(tooEarly, firstId)));
        assertThat(ledger.fail(alice, first.currentRunId(),
                ledger.execution(alice, first.currentRunId()).revision(), "MODEL_UNAVAILABLE")).isTrue();
        String secondId = id();
        Request second = ledger.create(alice, question, secondId, reuse(secondId, firstId));
        assertThat(second.status()).isEqualTo(Status.QUEUED);
        assertThat(second.snapshotOriginRequestId()).isEqualTo(firstId);
        assertThat(second.snapshotFileRef()).isEqualTo(frozen.fileRef());
        assertThat(second.currentRunId()).isNotNull();
        assertThat(ledger.create(alice, question, secondId, reuse(secondId, firstId)).currentRunId())
                .isEqualTo(second.currentRunId());
        assertConflict(() -> ledger.create(alice, question, secondId, reuse(secondId, secondId)));
        String foreignId = id();
        assertThatThrownBy(() -> ledger.create(bob, question, foreignId, reuse(foreignId, firstId)))
                .isInstanceOf(BusinessException.class);
        assertThat(ledger.fail(alice, second.currentRunId(),
                ledger.execution(alice, second.currentRunId()).revision(), "MODEL_UNAVAILABLE")).isTrue();
        String wrongQuestionId = id();
        assertThatThrownBy(() -> ledger.create(alice, otherQuestion, wrongQuestionId,
                reuse(wrongQuestionId, firstId))).isInstanceOf(BusinessException.class);
        String thirdId = id();
        Request third = ledger.create(alice, question, thirdId, reuse(thirdId, secondId));
        assertThat(third.snapshotOriginRequestId()).isEqualTo(firstId);
        assertThat(third.snapshotFileRef()).isEqualTo(frozen.fileRef());
        assertThat(ledger.detail(alice, question, thirdId).runs()).hasSize(1);
    }

    @Test
    void retryCreatesNewRunAndLateCancelOrPublishCannotChangeIt() {
        long account = account();
        String question = question(account);
        String requestId = id();
        Request first = captured(account, question, requestId);
        String oldRun = first.currentRunId();
        assertThat(ledger.fail(account, oldRun, ledger.execution(account, oldRun).revision(),
                "MODEL_UNAVAILABLE")).isTrue();
        String key = id();
        Request retried = ledger.retry(account, question, requestId, key, oldRun);
        String newRun = retried.currentRunId();
        assertThat(newRun).isNotEqualTo(oldRun);
        assertThat(retried.status()).isEqualTo(Status.QUEUED);
        assertThat(ledger.retry(account, question, requestId, key, oldRun).currentRunId())
                .isEqualTo(newRun);
        assertConflict(() -> ledger.retry(account, question, requestId, id(), oldRun));
        assertConflict(() -> ledger.cancel(account, question, requestId, oldRun));
        assertThat(ledger.publish(account, oldRun, ledger.execution(account, oldRun).revision(),
                new ResultDraft("旧结果", List.of()))).isFalse();
        assertThat(ledger.get(account, question, requestId).currentRunId()).isEqualTo(newRun);
        assertThat(ledger.detail(account, question, requestId).runs()).hasSize(2);
        assertThat(ledger.detail(account, question, requestId).runs().get(0).status())
                .isEqualTo(Status.FAILED);
    }

    @Test
    void cancelWinsPublishFenceAndSuccessfulPublishWinsLaterCancel() {
        long account = account();
        String question = question(account);
        Request first = captured(account, question, id());
        Execution running = start(account, first.currentRunId());
        Request cancellation = ledger.cancel(account, question, first.requestId(), first.currentRunId());
        assertThat(cancellation.status()).isIn(Status.CANCELLING, Status.CANCELLED);
        assertThat(ledger.publish(account, first.currentRunId(), running.revision(),
                new ResultDraft("不能发布", List.of()))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_result WHERE run_id = ?", Integer.class,
                first.currentRunId())).isZero();
        assertThat(ledger.cancel(account, question, first.requestId(), first.currentRunId()).requestId())
                .isEqualTo(first.requestId());

        // 独立账号确保前一个取消中调用不会占用本账号活动槽位。
        long otherAccount = account();
        String otherQuestion = question(otherAccount);
        Request second = captured(otherAccount, otherQuestion, id());
        Execution otherRunning = start(otherAccount, second.currentRunId());
        assertThat(ledger.publish(otherAccount, second.currentRunId(), otherRunning.revision(),
                new ResultDraft("已核验的提示", List.of()))).isTrue();
        assertThat(ledger.cancel(otherAccount, otherQuestion, second.requestId(), second.currentRunId()).status())
                .isEqualTo(Status.SUCCEEDED);
        assertThat(ledger.detail(otherAccount, otherQuestion, second.requestId()).result().bodyMarkdown())
                .isEqualTo("已核验的提示");
    }

    @Test
    void callIntentCannotBeResentAndUnknownRequiresExplicitRetry() {
        long account = account();
        String question = question(account);
        Request request = captured(account, question, id());
        String runId = request.currentRunId();
        start(account, runId);
        Attempt attempt = ledger.beginAttempt(account, runId, Role.SOLVE, 1, digest("input"));
        assertThat(attempt.attemptNo()).isEqualTo(1);
        assertThat(ledger.callIntent(account, runId, attempt.attemptId(), attempt.fence())).isTrue();
        // 重放到已持久化 CALL_INTENT 的同一步，不能视为一次安全重发。
        ledger.beginAttempt(account, runId, Role.SOLVE, 1, digest("input"));
        assertThat(ledger.get(account, question, request.requestId()).status()).isEqualTo(Status.FAILED);
        assertThat(ledger.detail(account, question, request.requestId()).run().reason())
                .isEqualTo("OUTCOME_UNKNOWN");
        assertThat(ledger.confirm(account, runId, attempt.attemptId(), attempt.fence(),
                "迟到响应", digest("迟到响应"))).isFalse();
        assertThat(ledger.providerCallsInFlight(account)).isEqualTo(1);
        assertThat(ledger.finishProviderCall(account, runId, attempt.attemptId())).isTrue();
        Request retried = ledger.retry(account, question, request.requestId(), id(), runId);
        assertThat(retried.currentRunId()).isNotEqualTo(runId);
        assertThat(ledger.execution(account, retried.currentRunId()).modelCalls()).isZero();
        start(account, retried.currentRunId());
        Attempt newAttempt = ledger.beginAttempt(account, retried.currentRunId(), Role.SOLVE, 1,
                digest("new-input"));
        assertThat(ledger.callIntent(account, retried.currentRunId(), newAttempt.attemptId(),
                newAttempt.fence())).isTrue();
        assertThat(ledger.unknown(account, retried.currentRunId(), newAttempt.attemptId(),
                newAttempt.fence())).isTrue();
        assertThat(ledger.get(account, question, request.requestId()).status()).isEqualTo(Status.FAILED);
    }

    @Test
    void confirmedOutputIsReusableAndModelCallsStopAtEight() {
        long account = account();
        String question = question(account);
        Request request = captured(account, question, id());
        String runId = request.currentRunId();
        start(account, runId);
        Attempt confirmed = ledger.beginAttempt(account, runId, Role.SOLVE, 1, digest("one"));
        assertThat(ledger.callIntent(account, runId, confirmed.attemptId(), confirmed.fence())).isTrue();
        assertThat(ledger.confirm(account, runId, confirmed.attemptId(), confirmed.fence(),
                "output", digest("output"))).isTrue();
        assertThat(ledger.beginAttempt(account, runId, Role.SOLVE, 1, digest("one")).output())
                .isEqualTo("output");
        for (int sequence = 2; sequence <= 8; sequence++) {
            Attempt attempt = ledger.beginAttempt(account, runId, Role.SOLVE, sequence,
                    digest("input-" + sequence));
            assertThat(ledger.callIntent(account, runId, attempt.attemptId(), attempt.fence())).isTrue();
            assertThat(ledger.confirm(account, runId, attempt.attemptId(), attempt.fence(),
                    "result-" + sequence, digest("result-" + sequence))).isTrue();
        }
        assertThat(ledger.execution(account, runId).modelCalls()).isEqualTo(8);
        assertThatThrownBy(() -> ledger.beginAttempt(account, runId, Role.SOLVE, 9, digest("nine")))
                .isInstanceOf(BusinessException.class);
        assertThat(ledger.execution(account, runId).modelCalls()).isEqualTo(8);
    }

    @Test
    void deadlinesCloseWaitAndQueueAndOutboxIsCommittedOnce() {
        long account = account();
        String question = question(account);
        String waitingId = id();
        ledger.create(account, question, waitingId, create(waitingId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox WHERE account_id = ?",
                Integer.class, account)).isZero();
        assertThat(ledger.reconcileDeadlines(Instant.now().plusSeconds(3600), 10)).isPositive();
        assertThat(ledger.get(account, question, waitingId).status()).isEqualTo(Status.TIMED_OUT);
        String queuedId = id();
        Request queued = captured(account, question, queuedId);
        assertThat(ledger.outstandingEvents(100)).anySatisfy(event -> {
            assertThat(event.aggregateId()).isEqualTo(queued.currentRunId());
            assertThat(event.status()).isEqualTo("PENDING");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox WHERE aggregate_id = ?",
                Integer.class, queued.currentRunId())).isEqualTo(1);
        assertThat(ledger.reconcileDeadlines(Instant.now().plusSeconds(3600), 10)).isPositive();
        assertThat(ledger.get(account, question, queuedId).status()).isEqualTo(Status.TIMED_OUT);
        assertThat(ledger.start(account, queued.currentRunId(),
                ledger.execution(account, queued.currentRunId()).revision())).isEmpty();
    }

    @Test
    void externalTransactionRollbackLeavesNoRequestRunOrOutbox() {
        long account = account();
        String question = question(account);
        String requestId = id();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            ledger.create(account, question, requestId, create(requestId));
            throw new IllegalStateException("rollback marker");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_request WHERE request_id = ?",
                Integer.class, requestId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_run WHERE request_id = ?",
                Integer.class, requestId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox WHERE account_id = ?",
                Integer.class, account)).isZero();
        assertThat(ledger.create(account, question, requestId, create(requestId)).status())
                .isEqualTo(Status.WAITING_INPUT);
        SnapshotBinding snapshot = binding(account, requestId);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            ledger.bindSnapshot(account, question, requestId, snapshot);
            throw new IllegalStateException("rollback bound snapshot");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.get(account, question, requestId).status()).isEqualTo(Status.WAITING_INPUT);
        assertThat(ledger.get(account, question, requestId).snapshotFileRef()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_run WHERE request_id = ?",
                Integer.class, requestId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_source WHERE origin_id = ?",
                Integer.class, requestId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox WHERE account_id = ?",
                Integer.class, account)).isZero();
    }

    @Test
    void legacyAndNewCreationShareAccountSlotEvenWhenStartedTogether() throws Exception {
        long account = account();
        String question = question(account);
        String legacyRequest = id();
        String newRequest = id();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> old = pool.submit(() -> attemptAfterGate(ready, go,
                    () -> legacy.createOrGet(account, question, legacyRequest, "旧轮次")));
            Future<Boolean> modern = pool.submit(() -> attemptAfterGate(ready, go,
                    () -> ledger.create(account, question, newRequest, create(newRequest))));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(old.get(), modern.get())).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tutor_turn WHERE account_id = ? AND status = 'WAITING_ANSWER'",
                    Integer.class, account) + jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ma_request WHERE account_id = ? AND status = 'WAITING_INPUT'",
                    Integer.class, account)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        long retryAccount = account();
        String retryQuestion = question(retryAccount);
        String turn = legacy.createOrGet(retryAccount, retryQuestion, id(), "旧轮次")
                .turn().getTurnId();
        String fileRef = id();
        legacy.bind(retryAccount, retryQuestion, turn, fileRef, digest(fileRef));
        assertThat(legacy.claim(retryAccount, retryQuestion, turn, false).claimed()).isTrue();
        legacy.finish(retryAccount, retryQuestion, turn, fileRef, null, "MODEL_UNAVAILABLE", "测试失败");
        String activeId = id();
        ledger.create(retryAccount, retryQuestion, activeId, create(activeId));
        assertConflict(() -> legacy.claim(retryAccount, retryQuestion, turn, true));
    }

    @Test
    void keysetCursorStaysStableAcrossInsertAndRejectsForeignOrTamperedCursor() {
        long account = account();
        long other = account();
        String question = question(account);
        String secondQuestion = question(account);
        String otherQuestion = question(other);
        for (int index = 0; index < 4; index++) {
            String requestId = id();
            ledger.create(account, question, requestId, create(requestId));
            ledger.cancel(account, question, requestId, null);
        }
        RequestPage first = ledger.list(account, question, 2, null);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();
        String inserted = id();
        ledger.create(account, question, inserted, create(inserted));
        ledger.cancel(account, question, inserted, null);
        RequestPage second = ledger.list(account, question, 2, first.nextCursor());
        assertThat(second.items()).hasSize(2);
        assertThat(second.items().stream().map(Request::requestId).toList())
                .doesNotContain(inserted)
                .doesNotContainAnyElementsOf(first.items().stream().map(Request::requestId).toList());
        assertThat(second.nextCursor()).isNull();
        assertInvalid(() -> ledger.list(account, question, 2, id()));
        assertInvalid(() -> ledger.list(account, secondQuestion, 2, first.nextCursor()));
        assertInvalid(() -> ledger.list(other, otherQuestion, 2, first.nextCursor()));
    }

    @Test
    void identicalRequestIdCanBeCreatedIndependentlyByTwoAccounts() {
        long alice = account();
        long bob = account();
        String aliceQuestion = question(alice);
        String bobQuestion = question(bob);
        String sharedId = id();
        assertThat(ledger.create(alice, aliceQuestion, sharedId, create(sharedId)).requestId())
                .isEqualTo(sharedId);
        assertThat(ledger.create(bob, bobQuestion, sharedId, create(sharedId)).requestId())
                .isEqualTo(sharedId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_request WHERE request_id = ?",
                Integer.class, sharedId)).isEqualTo(2);
        assertThat(ledger.get(alice, aliceQuestion, sharedId).questionId()).isEqualTo(aliceQuestion);
        assertThat(ledger.get(bob, bobQuestion, sharedId).questionId()).isEqualTo(bobQuestion);
    }

    @Test
    void foreignOrWithdrawnEvidenceCannotProduceVerifiedResult() {
        long alice = account();
        long bob = account();
        String question = question(alice);
        Request request = captured(alice, question, id());
        String runId = request.currentRunId();
        start(alice, runId);
        Attempt retrieval = ledger.beginAttempt(alice, runId, Role.RETRIEVE, 1, digest("retrieve"));

        SourceContent foreign = note(bob, "foreign");
        Anchor foreignAnchor = anchor(foreign);
        sources.addAnchors(bob, foreign.source().sourceId(), foreign.version().version(), List.of(foreignAnchor));
        Evidence forged = new Evidence(id(), runId, retrieval.stepId(), "HISTORY",
                foreign.source().sourceId(), foreign.version().version(), foreignAnchor.anchorId(), "正文", null);
        assertThatThrownBy(() -> ledger.recordEvidence(alice, runId, List.of(forged)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ledger.publish(alice, runId, ledger.execution(alice, runId).revision(),
                new ResultDraft("错误引用", List.of(forged.evidenceId()))))
                .isInstanceOf(BusinessException.class);

        SourceContent owned = note(alice, "owned");
        Anchor ownedAnchor = anchor(owned);
        sources.addAnchors(alice, owned.source().sourceId(), owned.version().version(), List.of(ownedAnchor));
        Evidence valid = new Evidence(id(), runId, retrieval.stepId(), "HISTORY", owned.source().sourceId(),
                owned.version().version(), ownedAnchor.anchorId(), "正文", null);
        ledger.recordEvidence(alice, runId, List.of(valid));
        sources.withdraw(alice, owned.source().sourceId());
        assertThatThrownBy(() -> ledger.publish(alice, runId, ledger.execution(alice, runId).revision(),
                new ResultDraft("已撤销引用 [[evidence:" + valid.evidenceId() + "]]",
                        List.of(valid.evidenceId()))))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_result WHERE run_id = ?",
                Integer.class, runId)).isZero();
    }

    @Test
    void verifiedPublicationReplacesEvidenceMarkerWithServerCitationOnly() {
        long account = account();
        String question = question(account);
        Request request = captured(account, question, id());
        String runId = request.currentRunId();
        start(account, runId);
        Attempt retrieval = ledger.beginAttempt(account, runId, Role.RETRIEVE, 1, digest("retrieve"));
        SourceContent source = note(account, "published");
        Anchor target = anchor(source);
        sources.addAnchors(account, source.source().sourceId(), source.version().version(), List.of(target));
        Evidence evidence = new Evidence(id(), runId, retrieval.stepId(), "HISTORY", source.source().sourceId(),
                source.version().version(), target.anchorId(), "正文", null);
        ledger.recordEvidence(account, runId, List.of(evidence));
        String draft = "这里参考你的笔记 [[evidence:" + evidence.evidenceId() + "]]。";
        assertThat(ledger.publish(account, runId, ledger.execution(account, runId).revision(),
                new ResultDraft(draft, List.of(evidence.evidenceId())))).isTrue();
        String resultId = jdbc.queryForObject("SELECT result_id FROM ma_result WHERE run_id = ?",
                String.class, runId);
        String citationId = jdbc.queryForObject("SELECT citation_id FROM ma_citation WHERE result_id = ?",
                String.class, resultId);
        String published = ledger.detail(account, question, request.requestId()).result().bodyMarkdown();
        assertThat(published).contains("[[citation:" + citationId + "]]")
                .doesNotContain("[[evidence:")
                .doesNotContain(evidence.evidenceId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_citation WHERE result_id = ?",
                Integer.class, resultId)).isEqualTo(1);
    }

    @Test
    void forgedMissingAndUnknownCitationMarkersRollbackEntirePublication() {
        long account = account();
        String question = question(account);
        Request request = captured(account, question, id());
        String runId = request.currentRunId();
        start(account, runId);
        Attempt retrieval = ledger.beginAttempt(account, runId, Role.RETRIEVE, 1, digest("retrieve"));
        SourceContent source = note(account, "marker-negative");
        Anchor target = anchor(source);
        sources.addAnchors(account, source.source().sourceId(), source.version().version(), List.of(target));
        Evidence evidence = new Evidence(id(), runId, retrieval.stepId(), "HISTORY", source.source().sourceId(),
                source.version().version(), target.anchorId(), "正文", null);
        ledger.recordEvidence(account, runId, List.of(evidence));
        long revision = ledger.execution(account, runId).revision();
        assertThatThrownBy(() -> ledger.publish(account, runId, revision,
                new ResultDraft("伪造 [[citation:" + id() + "]]", List.of(evidence.evidenceId()))))
                .isInstanceOf(BusinessException.class);
        assertNoResult(runId);
        assertThatThrownBy(() -> ledger.publish(account, runId, revision,
                new ResultDraft("缺少引用占位", List.of(evidence.evidenceId()))))
                .isInstanceOf(BusinessException.class);
        assertNoResult(runId);
        assertThatThrownBy(() -> ledger.publish(account, runId, revision,
                new ResultDraft("未知 [[evidence:" + id() + "]]", List.of(evidence.evidenceId()))))
                .isInstanceOf(BusinessException.class);
        assertNoResult(runId);
        sources.withdraw(account, source.source().sourceId());
        assertThatThrownBy(() -> ledger.publish(account, runId, revision,
                new ResultDraft("失效 [[evidence:" + evidence.evidenceId() + "]]",
                        List.of(evidence.evidenceId()))))
                .isInstanceOf(BusinessException.class);
        assertNoResult(runId);
    }

    @Test
    void cancellationKeepsSentProviderCallInFlightUntilExplicitFinish() {
        long account = account();
        String question = question(account);
        Request request = captured(account, question, id());
        start(account, request.currentRunId());
        Attempt attempt = ledger.beginAttempt(account, request.currentRunId(), Role.SOLVE, 1,
                digest("billable input"));
        assertThat(ledger.callIntent(account, request.currentRunId(), attempt.attemptId(),
                attempt.fence())).isTrue();
        assertThat(ledger.providerCallsInFlight(account)).isEqualTo(1);
        assertThat(ledger.providerCallsInFlight(null)).isEqualTo(1);
        ledger.cancel(account, question, request.requestId(), request.currentRunId());
        assertThat(ledger.providerCallsInFlight(account)).isEqualTo(1);
        assertThat(ledger.providerCallsInFlight(null)).isEqualTo(1);
        assertThat(ledger.finishProviderCall(account, request.currentRunId(), attempt.attemptId())).isTrue();
        assertThat(ledger.providerCallsInFlight(account)).isZero();
        assertThat(ledger.providerCallsInFlight(null)).isZero();
    }

    @Test
    void filteredRunStartIsVisibleBehindLargeSourceOutboxBacklog() {
        long account = account();
        String question = question(account);
        for (int index = 0; index < 30; index++) {
            note(account, "backlog-" + index);
        }
        String requestId = id();
        Request queued = captured(account, question, requestId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox WHERE kind = 'SOURCE_VERSION_CREATED' "
                + "AND status = 'PENDING'", Integer.class)).isGreaterThan(1);
        assertThat(ledger.outstandingEvents(Set.of("RUN_START"), 1))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.kind()).isEqualTo("RUN_START");
                    assertThat(event.aggregateId()).isEqualTo(queued.currentRunId());
                });
    }

    @Test
    void providerDeadlineExpiresBeforeRunDeadlineAndReleasesAccountSlot() {
        long account = account();
        String question = question(account);
        Request request = captured(account, question, id());
        String runId = request.currentRunId();
        start(account, runId);
        Attempt attempt = ledger.beginAttempt(account, runId, Role.SOLVE, 1, digest("input"));
        assertThat(ledger.callIntent(account, runId, attempt.attemptId(), attempt.fence())).isTrue();
        Instant now = Instant.now();
        assertThat(jdbc.queryForObject("SELECT run_deadline_at > ? FROM ma_run WHERE run_id = ?",
                Boolean.class, java.sql.Timestamp.from(now), runId)).isTrue();
        jdbc.update("UPDATE ma_attempt SET provider_deadline_at = ? WHERE attempt_id = ?",
                java.sql.Timestamp.from(now.minusSeconds(1)), attempt.attemptId());
        assertThat(ledger.reconcileDeadlines(now, 10)).isPositive();
        assertThat(ledger.detail(account, question, request.requestId()).run().status())
                .isEqualTo(Status.FAILED);
        assertThat(ledger.detail(account, question, request.requestId()).run().reason())
                .isEqualTo("OUTCOME_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT provider_call_state FROM ma_attempt WHERE attempt_id = ?",
                String.class, attempt.attemptId())).isEqualTo("UNKNOWN");
        assertThat(ledger.providerCallsInFlight(account)).isZero();
        String nextId = id();
        assertThat(ledger.create(account, question, nextId, create(nextId)).status())
                .isEqualTo(Status.WAITING_INPUT);
    }

    @Test
    void evidenceIdReplayAcceptsIdenticalDataAndRejectsChangedData() {
        long account = account();
        String question = question(account);
        Request request = captured(account, question, id());
        String runId = request.currentRunId();
        start(account, runId);
        Attempt retrieval = ledger.beginAttempt(account, runId, Role.RETRIEVE, 1, digest("retrieve"));
        Evidence first = new Evidence(id(), runId, retrieval.stepId(), "DERIVATION", null,
                null, null, "从当前题推导", null);
        ledger.recordEvidence(account, runId, List.of(first));
        ledger.recordEvidence(account, runId, List.of(first));
        assertThat(ledger.evidence(account, runId)).containsExactly(first);
        Evidence changed = new Evidence(first.evidenceId(), runId, retrieval.stepId(), "DERIVATION",
                null, null, null, "不同推导", null);
        assertConflict(() -> ledger.recordEvidence(account, runId, List.of(changed)));
        assertThat(ledger.evidence(account, runId)).containsExactly(first);
    }

    @Test
    void publicationRejectsFourUniqueCitationsButAcceptsThreeWithoutPartialRows() {
        long account = account();
        String question = question(account);
        Request request = captured(account, question, id());
        String runId = request.currentRunId();
        start(account, runId);
        Attempt step = ledger.beginAttempt(account, runId, Role.RETRIEVE, 1, digest("retrieve"));
        List<Evidence> evidence = new java.util.ArrayList<>();
        for (int index = 0; index < 4; index++) {
            evidence.add(historyEvidence(account, runId, step.stepId(), "citation-" + index));
        }
        ledger.recordEvidence(account, runId, evidence);
        long revision = ledger.execution(account, runId).revision();
        List<String> allIds = evidence.stream().map(Evidence::evidenceId).toList();
        assertThatThrownBy(() -> ledger.publish(account, runId, revision,
                new ResultDraft(markedBody(allIds), allIds))).isInstanceOf(BusinessException.class);
        assertNoResult(runId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox WHERE aggregate_type = 'RESULT'",
                Integer.class)).isZero();
        List<String> allowed = allIds.subList(0, 3);
        assertThat(ledger.publish(account, runId, revision,
                new ResultDraft(markedBody(allowed), allowed))).isTrue();
        String resultId = jdbc.queryForObject("SELECT result_id FROM ma_result WHERE run_id = ?",
                String.class, runId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_citation WHERE result_id = ?",
                Integer.class, resultId)).isEqualTo(3);
        assertThat(ledger.detail(account, question, request.requestId()).result().bodyMarkdown())
                .doesNotContain("[[evidence:");
    }

    @Test
    void duplicateEvidenceIdsInDraftRejectPublicationAtomically() {
        long account = account();
        String question = question(account);
        Request request = captured(account, question, id());
        String runId = request.currentRunId();
        start(account, runId);
        Attempt step = ledger.beginAttempt(account, runId, Role.RETRIEVE, 1, digest("retrieve"));
        Evidence evidence = historyEvidence(account, runId, step.stepId(), "duplicate");
        ledger.recordEvidence(account, runId, List.of(evidence));
        assertThatThrownBy(() -> ledger.publish(account, runId,
                ledger.execution(account, runId).revision(), new ResultDraft(
                        markedBody(List.of(evidence.evidenceId())),
                        List.of(evidence.evidenceId(), evidence.evidenceId()))))
                .isInstanceOf(BusinessException.class);
        assertNoResult(runId);
    }

    @Test
    void recordEvidenceRejectsForgedClaimAndSourceTypeMismatches() {
        long account = account();
        String question = question(account);
        Request request = captured(account, question, id());
        String runId = request.currentRunId();
        start(account, runId);
        Attempt step = ledger.beginAttempt(account, runId, Role.RETRIEVE, 1, digest("retrieve"));
        SourceContent note = note(account, "evidence-type");
        Anchor target = anchor(note);
        sources.addAnchors(account, note.source().sourceId(), note.version().version(), List.of(target));
        Evidence forgedClaim = new Evidence(id(), runId, step.stepId(), "HISTORY", note.source().sourceId(),
                note.version().version(), target.anchorId(), "伪造的推导", null);
        assertConflict(() -> ledger.recordEvidence(account, runId, List.of(forgedClaim)));
        Evidence currentOnNote = new Evidence(id(), runId, step.stepId(), "CURRENT_SNAPSHOT",
                note.source().sourceId(), note.version().version(), target.anchorId(), "正文", null);
        assertConflict(() -> ledger.recordEvidence(account, runId, List.of(currentOnNote)));
        Evidence derivationWithSource = new Evidence(id(), runId, step.stepId(), "DERIVATION",
                note.source().sourceId(), note.version().version(), target.anchorId(), "正文", null);
        assertConflict(() -> ledger.recordEvidence(account, runId, List.of(derivationWithSource)));
        assertThat(ledger.evidence(account, runId)).isEmpty();
        Evidence valid = new Evidence(id(), runId, step.stepId(), "HISTORY", note.source().sourceId(),
                note.version().version(), target.anchorId(), target.text(), null);
        ledger.recordEvidence(account, runId, List.of(valid));
        // 模拟补丁前已存的自造quote；发布边界也必须重新核对，不能只信旧账本行。
        jdbc.update("UPDATE ma_evidence SET claim = ? WHERE evidence_id = ?", "旧的伪造文本", valid.evidenceId());
        assertConflict(() -> ledger.publish(account, runId, ledger.execution(account, runId).revision(),
                new ResultDraft(markedBody(List.of(valid.evidenceId())), List.of(valid.evidenceId()))));
        assertNoResult(runId);
    }

    @Test
    void currentSnapshotEvidenceRequiresThisRunSourceAndHistoryCannotPointToSnapshot() throws Exception {
        long account = account();
        String question = question(account);
        jdbc.update("UPDATE question SET body_markdown = ?, summary = ? WHERE account_id = ? "
                + "AND question_id = ?", AnswerTestData.BODY, "求导", account, Long.parseLong(question));
        String requestId = id();
        AnswerModels.SaveManifest manifest = AnswerTestData.manifest(requestId, null);
        AnswerFileStore.Candidate candidate = files.save(manifest,
                new AnswerModels.QuestionSnapshot(question, "OTHER", AnswerTestData.BODY),
                List.of(part("manifest", json.writeValueAsBytes(manifest), MediaType.APPLICATION_JSON),
                part("chunk_0000", AnswerTestData.png(32), MediaType.IMAGE_PNG),
                part("question_0000", AnswerTestData.png(16), MediaType.IMAGE_PNG)));
        try {
            ledger.create(account, question, requestId, create(requestId));
            Request request = ledger.bindSnapshot(account, question, requestId,
                    new SnapshotBinding(candidate.fileRef(), candidate.requestHash(), id(), 1));
            String runId = request.currentRunId();
            start(account, runId);
            Attempt step = ledger.beginAttempt(account, runId, Role.RETRIEVE, 1, digest("retrieve"));
            Execution execution = ledger.execution(account, runId);
            SourceContent current = sources.get(account, execution.snapshotSourceId(), execution.snapshotVersion());
            Anchor currentAnchor = handwritingAnchor(current);
            Anchor badDigest = new Anchor(id(), currentAnchor.sourceId(), currentAnchor.version(),
                    currentAnchor.kind(), currentAnchor.ordinal(), "0".repeat(64),
                    currentAnchor.locatorJson(), currentAnchor.text());
            assertThatThrownBy(() -> sources.addAnchors(account, current.source().sourceId(),
                    current.version().version(), List.of(badDigest)))
                    .isInstanceOf(BusinessException.class);
            assertThat(sources.get(account, current.source().sourceId(), current.version().version()).anchors())
                    .isEmpty();
            sources.addAnchors(account, current.source().sourceId(), current.version().version(),
                    List.of(currentAnchor));
            Evidence allowed = new Evidence(id(), runId, step.stepId(), "CURRENT_SNAPSHOT",
                    current.source().sourceId(), current.version().version(), currentAnchor.anchorId(),
                    "手写推导", null);
            ledger.recordEvidence(account, runId, List.of(allowed));
            Evidence historyOnSnapshot = new Evidence(id(), runId, step.stepId(), "HISTORY",
                    current.source().sourceId(), current.version().version(), currentAnchor.anchorId(),
                    "手写推导", null);
            assertConflict(() -> ledger.recordEvidence(account, runId, List.of(historyOnSnapshot)));

            SourceContent other = sources.publish(account, new PublishSource(SourceType.TUTOR_SNAPSHOT,
                    id(), "other snapshot", null, candidate.requestHash(), candidate.fileRef(),
                    null, null, id()));
            Anchor otherAnchor = handwritingAnchor(other);
            sources.addAnchors(account, other.source().sourceId(), other.version().version(), List.of(otherAnchor));
            Evidence stolen = new Evidence(id(), runId, step.stepId(), "CURRENT_SNAPSHOT",
                    other.source().sourceId(), other.version().version(), otherAnchor.anchorId(),
                    "手写推导", null);
            assertConflict(() -> ledger.recordEvidence(account, runId, List.of(stolen)));
            assertThat(ledger.evidence(account, runId)).containsExactly(allowed);
        } finally {
            files.discard(candidate);
        }
    }

    private static Part part(String name, byte[] bytes, MediaType type) {
        MockPart part = new MockPart(name, name, bytes);
        part.getHeaders().setContentType(type);
        return part;
    }

    private Anchor handwritingAnchor(SourceContent content) {
        String locator = "{\"schemaVersion\":1,\"markdownLocator\":null,"
                + "\"handwritingLocator\":{\"segments\":[{\"blockIndex\":0,"
                + "\"questionRaster\":false,\"blockWorldBounds\":{\"x\":-8.0,\"y\":-8.0,"
                + "\"width\":32.0,\"height\":32.0},\"pixelWidth\":32,\"pixelHeight\":32,"
                + "\"regionWorldBounds\":{\"x\":-8.0,\"y\":-8.0,\"width\":32.0,"
                + "\"height\":32.0},\"strokeIds\":[\"s-1\"]}]}}";
        return new Anchor(id(), content.source().sourceId(), content.version().version(),
                "HANDWRITING_STEP", 0, digest("手写推导"), locator, "手写推导");
    }

    private Evidence historyEvidence(long account, String runId, String stepId, String origin) {
        SourceContent source = note(account, origin);
        Anchor target = anchor(source);
        sources.addAnchors(account, source.source().sourceId(), source.version().version(), List.of(target));
        return new Evidence(id(), runId, stepId, "HISTORY", source.source().sourceId(),
                source.version().version(), target.anchorId(), "正文", null);
    }

    private static String markedBody(List<String> evidenceIds) {
        return "提示：" + evidenceIds.stream().map(value -> "[[evidence:" + value + "]]")
                .collect(java.util.stream.Collectors.joining("、"));
    }

    private void assertNoResult(String runId) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_result WHERE run_id = ?",
                Integer.class, runId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_citation c JOIN ma_result r "
                + "ON c.result_id = r.result_id WHERE r.run_id = ?", Integer.class, runId)).isZero();
    }

    private SourceContent note(long account, String origin) {
        return sources.publish(account, new PublishSource(SourceType.NOTE, origin + id(), "note", "正文",
                digest("正文"), null, null, null, id()));
    }

    private Anchor anchor(SourceContent source) {
        return new Anchor(id(), source.source().sourceId(), source.version().version(),
                "MARKDOWN_BLOCK", 0, digest("正文"),
                "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,"
                        + "\"startOffset\":0,\"endOffset\":2},\"handwritingLocator\":null}", "正文");
    }

    private static boolean attemptAfterGate(CountDownLatch ready, CountDownLatch go,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action) throws InterruptedException {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("gate timed out");
        }
        try {
            action.call();
            return true;
        } catch (BusinessException expected) {
            return false;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAM));
    }

    private Request captured(long account, String question, String requestId) {
        ledger.create(account, question, requestId, create(requestId));
        return ledger.bindSnapshot(account, question, requestId, binding(account, requestId));
    }

    private Execution start(long account, String runId) {
        Execution queued = ledger.execution(account, runId);
        return ledger.start(account, runId, queued.revision()).orElseThrow();
    }

    private SnapshotBinding binding(long account, String originRequestId) {
        String fileRef = id();
        String sha = digest(fileRef);
        return new SnapshotBinding(fileRef, sha, id(), 1);
    }

    private long account() {
        return jdbc.queryForObject("INSERT INTO app_user(username, password_hash) VALUES (?, ?) "
                + "RETURNING account_id", Long.class, "u_" + id().replace("-", "").substring(0, 20), "test-hash");
    }

    private String question(long account) {
        Long questionId = jdbc.queryForObject("INSERT INTO question(account_id, request_id, subject, "
                + "body_markdown, summary) VALUES (?, ?, 'OTHER', '题目', '题目') RETURNING question_id",
                Long.class, account, id());
        return Long.toString(questionId);
    }

    private static CreateRequest create(String requestId) {
        return new CreateRequest(requestId, "请提示解法", Mode.HINT, new SnapshotInput("NEW_CAPTURE", null));
    }

    private static CreateRequest reuse(String requestId, String sourceRequestId) {
        return new CreateRequest(requestId, "沿用快照追问", Mode.HINT,
                new SnapshotInput("REUSE_REQUEST", sourceRequestId));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class);
    }
}
