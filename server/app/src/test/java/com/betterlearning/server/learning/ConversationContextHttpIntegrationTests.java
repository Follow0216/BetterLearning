package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.RolloutGate;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.multiagent.MultiAgentModels;
import com.betterlearning.server.multiagent.TutorLedger;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;
import com.betterlearning.server.support.TestHttpClient;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockPart;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import jakarta.servlet.http.Part;
import tools.jackson.databind.json.JsonMapper;

/** 真实 HTTP/PG 上下文边界；父轮由本地账本发布合成内容，不调用模型或连接共享服务。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class ConversationContextHttpIntegrationTests {
    private static final String PASSWORD = "Learn-Pass123";
    private static final Path FILE_ROOT = temporaryRoot();
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "conversation-http-isolated-test-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "conversation-http-test", "conversation-client");
        IntegrationProperties.registerRateLimits(registry);
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
        registry.add("app.files.root", FILE_ROOT::toString);
        registry.add("app.multi-agent.rollout.mode", () -> "V2");
        registry.add("app.multi-agent.rollout.transition-from", () -> "LEGACY");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JsonMapper json;

    @Autowired
    private TutorLedger ledger;

    @Autowired
    private RolloutGate rollout;

    @Autowired
    private AnswerFileStore files;

    private TestHttpClient http;

    @BeforeEach
    void clean() {
        http = new TestHttpClient(port, json);
        TestData.clean(jdbc, redis);
        rollout.switchTo("V2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '2026102603' AND success", Integer.class)).isEqualTo(1);
    }

    @AfterEach
    void noModelAttemptWasCreated() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_attempt", Integer.class)).isZero();
    }

    @AfterAll
    static void removeOwnedFiles() throws IOException {
        try (var paths = Files.walk(FILE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void missingAndExplicitNullParentHaveSameLegacyIdempotencyAndEcho() throws Exception {
        String token = account("ctx_legacy");
        String question = question(token);
        String id = id();
        Map<String, Object> body = command(id, "独立提示");
        assertThat(accepted(root(question), token, id, body))
                .containsEntry("contextParentRequestId", null).containsEntry("status", "WAITING_INPUT");
        body.put("contextParentRequestId", null);
        assertThat(accepted(root(question), token, id, body)).containsEntry("requestId", id)
                .containsEntry("contextParentRequestId", null);
        assertThat(map(http.jsonBody(http.get(root(question) + "/" + id, token)).get("request")))
                .containsEntry("contextParentRequestId", null);
        assertThat(map(http.jsonBody(http.get(root(question) + "/pending", token)).get("request")))
                .containsEntry("contextParentRequestId", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_request", Integer.class)).isEqualTo(1);
        ledger.cancel(accountId("ctx_legacy"), question, id, null);
        String explicitId = id();
        Map<String, Object> explicit = command(explicitId, "显式独立提示");
        explicit.put("contextParentRequestId", null);
        accepted(root(question), token, explicitId, explicit);
        explicit.remove("contextParentRequestId");
        assertThat(accepted(root(question), token, explicitId, explicit)).containsEntry("requestId", explicitId);
    }

    @Test
    void httpFollowupPinsSelectedPublishedParentAcrossCreateDetailListAndIdempotency() throws Exception {
        String token = account("ctx_parent");
        long account = accountId("ctx_parent");
        String question = question(token);
        MultiAgentModels.Request parent = published(account, question, null, "第二步：等式两边同时减3。");
        MultiAgentModels.Request later = published(account, question, parent.requestId(), "之后的一轮解释。");
        String id = id();
        Map<String, Object> body = command(id, "你刚才第二步为什么这样做？");
        body.put("contextParentRequestId", parent.requestId());
        body.put("snapshotInput", Map.of("kind", "REUSE_REQUEST", "sourceRequestId", later.requestId()));
        Map<String, Object> created = accepted(root(question), token, id, body);
        assertThat(created).containsEntry("contextParentRequestId", parent.requestId())
                .containsEntry("snapshotOriginRequestId", later.snapshotOriginRequestId());
        assertThat(map(http.jsonBody(http.get(root(question) + "/" + id, token)).get("request")))
                .containsEntry("contextParentRequestId", parent.requestId());
        assertThat(list(http.jsonBody(http.get(root(question) + "?limit=10", token)).get("items")))
                .anySatisfy(item -> {
                    assertThat(item).containsEntry("requestId", id)
                            .containsEntry("contextParentRequestId", parent.requestId());
                });
        MultiAgentModels.Execution execution = ledger.execution(account, (String) created.get("currentRunId"));
        assertThat(execution.conversationContext().turns()).singleElement().satisfies(turn -> {
            assertThat(turn.requestId()).isEqualTo(parent.requestId());
            assertThat(turn.prompt()).isEqualTo("请解释第二步");
            assertThat(turn.bodyMarkdown()).isEqualTo("第二步：等式两边同时减3。");
        });
        String frozen = jdbc.queryForObject("SELECT conversation_context_json FROM ma_request WHERE request_id = ?",
                String.class, id);
        accepted(root(question), token, id, body);
        body.put("contextParentRequestId", later.requestId());
        http.assertErrorEnvelope(action(root(question), token, id, body), 409, "A0455");
        assertThat(jdbc.queryForObject("SELECT conversation_context_json FROM ma_request WHERE request_id = ?",
                String.class, id)).isEqualTo(frozen);
    }

    @Test
    void httpRejectsInvalidForeignAndUnsuccessfulParentsWithoutPublishingRequests() throws Exception {
        String owner = account("ctx_owner");
        String other = account("ctx_other");
        long account = accountId("ctx_owner");
        String question = question(owner);
        String sameAccountOtherQuestion = question(owner);
        String foreignQuestion = question(other);
        MultiAgentModels.Request parent = published(account, question, null, "合法父轮。");
        for (String invalid : List.of("", "bad-uuid", parent.requestId().toUpperCase())) {
            String id = id();
            Map<String, Object> body = command(id, "无效父轮");
            body.put("contextParentRequestId", invalid);
            http.assertErrorEnvelope(action(root(question), owner, id, body), 400, "A0400");
            assertMissingRequest(id);
        }
        String self = id();
        Map<String, Object> selfBody = command(self, "自指父轮");
        selfBody.put("contextParentRequestId", self);
        http.assertErrorEnvelope(action(root(question), owner, self, selfBody), 400, "A0400");
        assertMissingRequest(self);
        for (Map.Entry<String, String> target : Map.of(sameAccountOtherQuestion, owner, foreignQuestion, other).entrySet()) {
            String id = id();
            Map<String, Object> body = command(id, "跨题或账号");
            body.put("contextParentRequestId", parent.requestId());
            http.assertErrorEnvelope(action(root(target.getKey()), target.getValue(), id, body), 404, "A0606");
            assertMissingRequest(id);
        }
        MultiAgentModels.Request failed = captured(account, question);
        MultiAgentModels.Execution queued = ledger.execution(account, failed.currentRunId());
        MultiAgentModels.Execution running = ledger.start(account, queued.runId(), queued.revision()).orElseThrow();
        assertThat(ledger.fail(account, running.runId(), running.revision(), "MODEL_UNAVAILABLE")).isTrue();
        String id = id();
        Map<String, Object> body = command(id, "失败轮次不可承接");
        body.put("contextParentRequestId", failed.requestId());
        http.assertErrorEnvelope(action(root(question), owner, id, body), 409, "A0456");
        assertMissingRequest(id);
    }

    @Test
    void httpRejectsWholeParentOverUtf8BudgetWithoutCreatingRequest() throws Exception {
        String token = account("ctx_budget");
        String question = question(token);
        MultiAgentModels.Request parent = published(accountId("ctx_budget"), question, null, "字".repeat(23000));
        String id = id();
        Map<String, Object> body = command(id, "追问超预算父轮");
        body.put("contextParentRequestId", parent.requestId());
        http.assertErrorEnvelope(action(root(question), token, id, body), 413, "A0431");
        assertMissingRequest(id);
        body.remove("contextParentRequestId");
        assertThat(accepted(root(question), token, id, body)).containsEntry("contextParentRequestId", null);
    }

    private MultiAgentModels.Request published(long account, String question, String parent, String body) throws Exception {
        String id = id();
        ledger.create(account, question, id, new MultiAgentModels.CreateRequest(id, "请解释第二步",
                MultiAgentModels.Mode.FULL_SOLUTION, null, parent));
        MultiAgentModels.Request captured = bind(account, question, id);
        MultiAgentModels.Execution queued = ledger.execution(account, captured.currentRunId());
        MultiAgentModels.Execution running = ledger.start(account, queued.runId(), queued.revision()).orElseThrow();
        assertThat(ledger.publish(account, running.runId(), running.revision(),
                new MultiAgentModels.ResultDraft(body, List.of()))).isTrue();
        return ledger.get(account, question, id);
    }

    private MultiAgentModels.Request captured(long account, String question) throws Exception {
        String id = id();
        ledger.create(account, question, id, new MultiAgentModels.CreateRequest(id, "新的独立问题",
                MultiAgentModels.Mode.HINT, null));
        return bind(account, question, id);
    }

    private MultiAgentModels.Request bind(long account, String question, String id) throws Exception {
        AnswerModels.SaveManifest manifest = AnswerTestData.manifest(id, null);
        List<Part> parts = List.of(
                part("manifest", MediaType.APPLICATION_JSON_VALUE, json.writeValueAsBytes(manifest)),
                part("chunk_0000", MediaType.IMAGE_PNG_VALUE, AnswerTestData.png(32)),
                part("question_0000", MediaType.IMAGE_PNG_VALUE, AnswerTestData.png(16)));
        AnswerFileStore.Candidate candidate = files.save(manifest,
                new AnswerModels.QuestionSnapshot(question, "OTHER", AnswerTestData.BODY), parts);
        return ledger.bindSnapshot(account, question, id,
                new MultiAgentModels.SnapshotBinding(candidate.fileRef(), candidate.requestHash(), null, 0));
    }

    private static MockPart part(String name, String contentType, byte[] bytes) {
        MockPart part = new MockPart(name, name, bytes);
        part.getHeaders().setContentType(MediaType.parseMediaType(contentType));
        return part;
    }

    private Map<String, Object> accepted(String path, String token, String id, Object body) throws Exception {
        HttpResponse<String> response = action(path, token, id, body);
        assertThat(response.statusCode()).isEqualTo(200);
        return http.jsonBody(response);
    }

    private HttpResponse<String> action(String path, String token, String id, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(http.uri(path)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").header("Idempotency-Key", id)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    private String account(String username) throws Exception {
        assertThat(http.post("/api/v1/users", TestData.registerJson(username, PASSWORD)).statusCode()).isEqualTo(201);
        return (String) http.jsonBody(http.post("/api/v1/sessions", TestData.loginJson(username, PASSWORD)))
                .get("accessToken");
    }

    private long accountId(String username) {
        return jdbc.queryForObject("SELECT account_id FROM app_user WHERE username = ?", Long.class, username);
    }

    private String question(String token) throws Exception {
        HttpResponse<String> response = http.post("/api/v1/learning/questions", json.writeValueAsString(Map.of(
                "requestId", id(), "subject", "OTHER", "bodyMarkdown", AnswerTestData.BODY)), token);
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(response).get("questionId");
    }

    private void assertMissingRequest(String id) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_request WHERE request_id = ?", Integer.class, id)).isZero();
    }

    private static Map<String, Object> command(String id, String prompt) {
        return new HashMap<>(Map.of("requestId", id, "prompt", prompt, "mode", "HINT"));
    }

    private static String root(String question) {
        return "/api/v2/learning/questions/" + question + "/tutor-requests";
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("bl-conversation-http-it-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
