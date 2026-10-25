package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.betterlearning.server.ServerApplication;
import com.betterlearning.server.agent.LedgerStepExecutor;
import com.betterlearning.server.multiagent.MultiAgentModels.CreateRequest;
import com.betterlearning.server.multiagent.MultiAgentModels.Mode;
import com.betterlearning.server.multiagent.MultiAgentModels.Request;
import com.betterlearning.server.multiagent.MultiAgentModels.Role;
import com.betterlearning.server.multiagent.MultiAgentModels.SnapshotBinding;
import com.betterlearning.server.multiagent.MultiAgentModels.SnapshotInput;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.betterlearning.server.multiagent.MultiAgentModels.StepInvocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StepOutput;
import com.betterlearning.server.multiagent.TutorLedger;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 验证真实账本上的逐角色恢复边界；action 是本地替身，不评估模型内容。 */
@Testcontainers
@SpringBootTest(classes = ServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class StatefulStepExecutorIntegrationTests {
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-step-executor-test-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "step-test", "step-client");
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

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, redis);
    }

    @Test
    void confirmedOutputsForFourModelRolesAreReusedWithoutCallingActionAgain() {
        Fixture fixture = running();
        LedgerStepExecutor executor = new LedgerStepExecutor(ledger, fixture.account(), fixture.runId());
        AtomicInteger calls = new AtomicInteger();
        for (Role role : new Role[] {Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW}) {
            StepInvocation invocation = new StepInvocation(role, 1, digest(role.name()));
            StepOutput expected = output(role.name() + " answer");
            assertThat(executor.execute(invocation, () -> {
                calls.incrementAndGet();
                return expected;
            })).isEqualTo(expected);
            assertThat(executor.execute(invocation, () -> {
                calls.incrementAndGet();
                throw new AssertionError("confirmed step invoked action again");
            })).isEqualTo(expected);
        }
        assertThat(calls).hasValue(4);
        assertThat(ledger.execution(fixture.account(), fixture.runId()).modelCalls()).isEqualTo(4);
    }

    @Test
    void callIntentOnRecoveryEndsRunWithoutCallingAction() {
        Fixture fixture = running();
        StepInvocation invocation = new StepInvocation(Role.SOLVE, 1, digest("input"));
        var attempt = ledger.beginAttempt(fixture.account(), fixture.runId(), invocation.role(),
                invocation.sequence(), invocation.inputDigest());
        assertThat(ledger.callIntent(fixture.account(), fixture.runId(), attempt.attemptId(),
                attempt.fence())).isTrue();
        AtomicInteger calls = new AtomicInteger();
        LedgerStepExecutor executor = new LedgerStepExecutor(ledger, fixture.account(), fixture.runId());
        try {
            executor.execute(invocation, () -> {
                calls.incrementAndGet();
                return output("must not call");
            });
        } catch (RuntimeException expected) {
            // UNKNOWN 可抛出明确失败；账本终态和零 action 调用是本测试的断言。
        }
        assertThat(calls).hasValue(0);
        assertThat(ledger.get(fixture.account(), fixture.question(), fixture.requestId()).status())
                .isEqualTo(Status.FAILED);
        assertThat(ledger.detail(fixture.account(), fixture.question(), fixture.requestId()).run().reason())
                .isEqualTo("OUTCOME_UNKNOWN");
        assertThat(ledger.providerCallsInFlight(fixture.account())).isEqualTo(1);
    }

    @Test
    void thrownActionIsNotAutomaticallyRetriedAndRunBecomesUnknown() {
        Fixture fixture = running();
        LedgerStepExecutor executor = new LedgerStepExecutor(ledger, fixture.account(), fixture.runId());
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(new StepInvocation(Role.SOLVE, 1, digest("input")), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("provider failed after possible send");
        })).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(1);
        assertThat(ledger.get(fixture.account(), fixture.question(), fixture.requestId()).status())
                .isEqualTo(Status.FAILED);
        assertThat(ledger.detail(fixture.account(), fixture.question(), fixture.requestId()).run().reason())
                .isEqualTo("OUTCOME_UNKNOWN");
        assertThat(ledger.execution(fixture.account(), fixture.runId()).modelCalls()).isEqualTo(1);
        assertThat(ledger.providerCallsInFlight(fixture.account())).isEqualTo(1);
    }

    @Test
    void retrievalDoesNotUseModelBudgetAndCancelledRunNeverCallsAction() {
        Fixture fixture = running();
        LedgerStepExecutor executor = new LedgerStepExecutor(ledger, fixture.account(), fixture.runId());
        AtomicInteger calls = new AtomicInteger();
        StepInvocation retrieve = new StepInvocation(Role.RETRIEVE, 1, digest("query"));
        StepOutput retrieved = output("history candidates");
        assertThat(executor.execute(retrieve, () -> {
            calls.incrementAndGet();
            return retrieved;
        })).isEqualTo(retrieved);
        assertThat(ledger.execution(fixture.account(), fixture.runId()).modelCalls()).isZero();
        assertThat(executor.execute(retrieve, () -> {
            calls.incrementAndGet();
            throw new AssertionError("replayed retrieval invoked action");
        })).isEqualTo(retrieved);
        assertThat(calls).hasValue(1);
        ledger.cancel(fixture.account(), fixture.question(), fixture.requestId(), fixture.runId());
        try {
            executor.execute(new StepInvocation(Role.SOLVE, 1, digest("after-cancel")), () -> {
                calls.incrementAndGet();
                return output("forbidden");
            });
        } catch (RuntimeException expected) {
            // 已取消运行可拒绝执行；不能进入 action。
        }
        assertThat(calls).hasValue(1);
    }

    private Fixture running() {
        Long account = jdbc.queryForObject("INSERT INTO app_user(username, password_hash) VALUES (?, ?) "
                + "RETURNING account_id", Long.class, "u_" + id().replace("-", "").substring(0, 20), "test-hash");
        Long questionId = jdbc.queryForObject("INSERT INTO question(account_id, request_id, subject, "
                + "body_markdown, summary) VALUES (?, ?, 'OTHER', '题目', '题目') RETURNING question_id",
                Long.class, account, id());
        String question = Long.toString(questionId);
        String requestId = id();
        ledger.create(account, question, requestId,
                new CreateRequest(requestId, "提示", Mode.HINT, new SnapshotInput("NEW_CAPTURE", null)));
        String fileRef = id();
        Request request = ledger.bindSnapshot(account, question, requestId,
                new SnapshotBinding(fileRef, digest(fileRef), id(), 1));
        ledger.start(account, request.currentRunId(), ledger.execution(account, request.currentRunId()).revision())
                .orElseThrow();
        return new Fixture(account, question, requestId, request.currentRunId());
    }

    private static StepOutput output(String value) {
        return new StepOutput(value, digest(value));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(long account, String question, String requestId, String runId) { }
}
