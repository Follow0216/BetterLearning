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

import com.betterlearning.server.agent.TutorRequestService;
import com.betterlearning.server.agent.pipeline.DefaultRolePipeline;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Usage;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.RolloutGate;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.multiagent.MultiAgentModels;
import com.betterlearning.server.multiagent.RolePipeline;
import com.betterlearning.server.multiagent.SnapshotPort;
import com.betterlearning.server.multiagent.TutorLedger;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;
import com.betterlearning.server.support.TestHttpClient;

import jakarta.servlet.http.Part;
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
import tools.jackson.databind.json.JsonMapper;

/** 三个独立 HTTP/PG 样本；角色输出固定替身，无 provider/Temporal/设备调用。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class TextOnlyTutorHttpIntegrationTests {
    private static final String PASSWORD = "Learn-Pass123";
    private static final String PRESENCE = "/api/v2/learning/tablet-presence";
    private static final Path FILE_ROOT = temporaryRoot();

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();
    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, Base64.getEncoder().encodeToString(
                "text-only-http-isolated-test-secret".getBytes(StandardCharsets.UTF_8)), "text-only-test", "client");
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
    private SnapshotPort snapshots;
    @Autowired
    private TutorRequestService service;
    @Autowired
    private RolePipeline pipeline;
    private TestHttpClient http;

    @BeforeEach
    void clean() {
        http = new TestHttpClient(port, json);
        TestData.clean(jdbc, redis);
        rollout.switchTo("V2");
    }

    @AfterEach
    void noProviderAttempt() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_attempt", Integer.class)).isZero();
    }

    @AfterAll
    static void cleanOwnedFiles() throws IOException {
        try (var paths = Files.walk(FILE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void noTabletCompletesTextOnlyAndFreezesQuestionAndParentWithoutInventingSnapshot() throws Exception {
        String token = account("text_only");
        long account = accountId("text_only");
        String question = question(token);
        String request = id();
        Map<String, Object> created = create(token, question, request, null);
        assertThat(created).containsEntry("status", "QUEUED").containsEntry("snapshotFileRef", null)
                .containsEntry("snapshotOriginRequestId", null).containsEntry("inputSelectionReason", "NO_DEVICE");
        assertThat(map(created.get("snapshotInput"))).containsEntry("kind", "NO_ANSWER");
        String run = (String) created.get("currentRunId");
        assertThat(run).isNotBlank();
        String session = id();
        heartbeat(token, session, 1, question, true);
        assertThat(create(token, question, request, null)).isEqualTo(created);
        heartbeat(token, session, 2, null, false);
        var execution = ledger.execution(account, run);
        assertThat(execution.snapshotSourceId()).isNull();
        assertThat(execution.snapshotVersion()).isNull();
        assertThat(snapshots.load(account, request).answerInputKind()).isEqualTo(MultiAgentModels.AnswerInputKind.NONE);
        jdbc.update("UPDATE question SET body_markdown = ? WHERE account_id = ? AND question_id = ?",
                "后续编辑过的题面", account, Long.parseLong(question));
        assertThat(snapshots.load(account, request).questionMarkdown()).isEqualTo(AnswerTestData.BODY);
        ledger.start(account, run, execution.revision()).orElseThrow();
        var result = pipeline.execute(new MultiAgentModels.PipelineContext(ledger.execution(account, run),
                snapshots.load(account, request), List.of()), (invocation, forbiddenProviderAction) -> fixedRole(invocation.role()));
        assertThat(result.verified()).as(result.reason()).isTrue();
        assertThat(ledger.publish(account, run, ledger.execution(account, run).revision(),
                new MultiAgentModels.ResultDraft(result.bodyMarkdown(), result.evidenceIds()))).isTrue();
        Map<String, Object> detail = http.jsonBody(http.get(root(question) + "/" + request, token));
        assertThat(map(detail.get("request"))).containsEntry("status", "SUCCEEDED");
        assertThat(map(detail.get("result")).get("citations")).isEqualTo(List.of());
        String followup = id();
        Map<String, Object> next = create(token, question, followup, request);
        var context = ledger.execution(account, (String) next.get("currentRunId")).conversationContext();
        assertThat(context.turns()).singleElement().satisfies(turn -> {
            assertThat(turn.requestId()).isEqualTo(request);
            assertThat(turn.snapshotFileRef()).isNull();
            assertThat(turn.bodyMarkdown()).contains("幂函数");
        });
        String nextRun = (String) next.get("currentRunId");
        assertThat(ledger.fail(account, nextRun, ledger.execution(account, nextRun).revision(), "MODEL_UNAVAILABLE")).isTrue();
        String retryId = id();
        var retry = action(root(question) + "/" + followup + "/retry", token, retryId,
                Map.of("targetRunId", next.get("currentRunId")));
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(snapshots.load(account, followup).answerInputKind()).isEqualTo(MultiAgentModels.AnswerInputKind.NONE);
        String retryRun = ledger.get(account, question, followup).currentRunId();
        assertThat(action(root(question) + "/" + followup + "/cancel", token, id(),
                Map.of("expectedRunId", retryRun)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_source WHERE type = 'TUTOR_SNAPSHOT'", Integer.class)).isZero();
    }

    @Test
    void freshSameQuestionUsesRealZeroStrokeSnapshotAndAutoReplayNeverReselects() throws Exception {
        String token = account("text_tablet");
        long account = accountId("text_tablet");
        String question = question(token);
        String session = id();
        heartbeat(token, session, 1, question, true);
        String request = id();
        Map<String, Object> created = create(token, question, request, null);
        assertThat(created).containsEntry("status", "WAITING_INPUT").containsEntry("currentRunId", null)
                .containsEntry("inputSelectionReason", "TABLET_AVAILABLE");
        assertThat(map(created.get("snapshotInput"))).containsEntry("kind", "NEW_CAPTURE");
        heartbeat(token, session, 2, null, false);
        assertThat(create(token, question, request, null)).isEqualTo(created);
        var original = AnswerTestData.manifest(request, null);
        var empty = new AnswerModels.SaveManifest(original.schemaVersion(), original.saveRequestId(), original.baseFileRef(),
                original.canvas(), new AnswerModels.Document(original.document().schemaVersion(),
                original.document().questionLayout(), List.of()), original.blocks());
        List<Part> parts = List.of(part("manifest", MediaType.APPLICATION_JSON_VALUE, json.writeValueAsBytes(empty)),
                part("chunk_0000", MediaType.IMAGE_PNG_VALUE, AnswerTestData.png(32)),
                part("question_0000", MediaType.IMAGE_PNG_VALUE, AnswerTestData.png(16)));
        service.upload(account, question, request, empty, parts);
        var snapshot = snapshots.load(account, request);
        assertThat(snapshot.answerInputKind()).isEqualTo(MultiAgentModels.AnswerInputKind.SNAPSHOT);
        assertThat(snapshot.fileRef()).isNotBlank();
        assertThat(snapshot.hasHandwriting()).isFalse();
        assertThat(snapshot.images()).isNotEmpty();
        assertThat(snapshot.strokeIds()).isEmpty();
    }

    @Test
    void accountQuestionExpiryAndOutOfOrderReleaseDoNotMasqueradeAsAvailable() throws Exception {
        String token = account("text_owner");
        String foreign = account("text_foreign");
        String question = question(token);
        String otherQuestion = question(token);
        String foreignQuestion = question(foreign);
        String session = id();
        heartbeat(foreign, session, 1, foreignQuestion, true);
        assertThat(availability(token, question)).containsEntry("available", false).containsEntry("reason", "NO_DEVICE");
        heartbeat(token, session, 1, otherQuestion, true);
        assertThat(availability(token, question)).containsEntry("available", false).containsEntry("reason", "OTHER_QUESTION");
        heartbeat(token, session, 3, null, false);
        heartbeat(token, session, 2, question, true);
        assertThat(availability(token, question)).containsEntry("available", false).containsEntry("reason", "OFFLINE");
        heartbeat(token, session, 4, question, true);
        assertThat(availability(token, question)).containsEntry("available", true);
        jdbc.update("UPDATE ma_tablet_presence SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE account_id = ?",
                accountId("text_owner"));
        assertThat(availability(token, question)).containsEntry("available", false).containsEntry("reason", "OFFLINE");
        assertThat(http.get("/api/v2/learning/questions/" + question + "/tablet-availability", "invalid-token").statusCode()).isEqualTo(401);
        assertThat(http.get("/api/v2/learning/questions/" + question + "/tablet-availability", foreign).statusCode()).isEqualTo(404);
        var absent = create(token, question, id(), null);
        assertThat(absent).containsEntry("status", "QUEUED").containsEntry("inputSelectionReason", "OFFLINE");
    }

    private MultiAgentModels.StepOutput fixedRole(MultiAgentModels.Role role) {
        String output = switch (role) {
            case SOLVE -> "{\"summary\":\"幂函数求导\",\"answer\":\"2x\",\"methodTags\":[\"求导\"],\"checkpoints\":[\"指数乘系数\"],\"sufficient\":true,\"answerComplete\":true}";
            case DIAGNOSE -> "{\"status\":\"NO_ANSWER\",\"summary\":\"本轮未提供作答\",\"observations\":[],\"clarification\":\"\"}";
            case TEACH -> "{\"hint\":\"先回忆幂函数求导公式。\",\"nextStep\":\"将指数乘到系数，再把指数减一。\",\"clarification\":\"\",\"fullSolution\":null,\"evidenceIds\":[]}";
            case REVIEW -> "{\"approved\":true,\"solutionComplete\":true,\"mathematicsOk\":true,\"diagnosisGrounded\":true,\"hintSafe\":true,\"citationsSupported\":true,\"historyNotAuthority\":true,\"issues\":[]}";
            case RETRIEVE -> null;
        };
        Object stored = role == MultiAgentModels.Role.RETRIEVE ? new DefaultRolePipeline.StoredRetrieval(List.of())
                : new DefaultRolePipeline.StoredReply(output, null, "fixed-test-output", new Usage(10, 10, 1, false));
        String value = json.writeValueAsString(stored);
        return new MultiAgentModels.StepOutput(value, AnswerManifestValidator.sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> create(String token, String question, String request, String parent) throws Exception {
        var body = new HashMap<String, Object>(Map.of("requestId", request, "prompt", "请给一点提示", "mode", "HINT",
                "snapshotInput", Map.of("kind", "AUTO")));
        body.put("contextParentRequestId", parent);
        var response = action(root(question), token, request, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return http.jsonBody(response);
    }

    private void heartbeat(String token, String session, long sequence, String question, boolean active) throws Exception {
        var body = new HashMap<String, Object>(Map.of("sessionId", session, "sequence", sequence, "active", active));
        body.put("questionId", question);
        var response = action(PRESENCE, token, id(), body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private Map<String, Object> availability(String token, String question) throws Exception {
        var response = http.get("/api/v2/learning/questions/" + question + "/tablet-availability", token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return http.jsonBody(response);
    }

    private HttpResponse<String> action(String path, String token, String key, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(http.uri(path)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    private String account(String username) throws Exception {
        assertThat(http.post("/api/v1/users", TestData.registerJson(username, PASSWORD)).statusCode()).isEqualTo(201);
        return (String) http.jsonBody(http.post("/api/v1/sessions", TestData.loginJson(username, PASSWORD))).get("accessToken");
    }

    private long accountId(String username) {
        return jdbc.queryForObject("SELECT account_id FROM app_user WHERE username = ?", Long.class, username);
    }

    private String question(String token) throws Exception {
        var response = http.post("/api/v1/learning/questions", json.writeValueAsString(Map.of(
                "requestId", id(), "subject", "OTHER", "bodyMarkdown", AnswerTestData.BODY)), token);
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(response).get("questionId");
    }

    private static MockPart part(String name, String type, byte[] bytes) {
        var part = new MockPart(name, name, bytes);
        part.getHeaders().setContentType(MediaType.parseMediaType(type));
        return part;
    }

    private static String root(String question) {
        return "/api/v2/learning/questions/" + question + "/tutor-requests";
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("bl-text-only-http-it-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
