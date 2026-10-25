package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.multiagent.MultiAgentModels;
import com.betterlearning.server.multiagent.TutorLedger;
import com.betterlearning.server.multiagent.SnapshotPort;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;
import com.betterlearning.server.support.TestHttpClient;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

/** 真实 HTTP/数据库/文件边界；run 由测试端显式置失败，不代表真实模型验收。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class TutorRequestHttpIntegrationTests {
    private static final String QUESTIONS = "/api/v1/learning/questions";
    private static final String PASSWORD = "Learn-Pass123";
    private static final String BOUNDARY = "v2-boundary";
    private static final Path FILE_ROOT = temporaryRoot();
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-v2-http-integration-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "v2-http-test", "v2-client");
        IntegrationProperties.registerRateLimits(registry);
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
        registry.add("app.files.root", FILE_ROOT::toString);
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
    private SnapshotPort snapshots;

    private TestHttpClient http;

    @BeforeEach
    void clean() {
        http = new TestHttpClient(port, json);
        TestData.clean(jdbc, redis);
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
    void newCaptureOnlyQueuesAfterCompleteFilesAndKeepsFrozenBytes() throws Exception {
        String alice = account("v2_alice");
        String bob = account("v2_bob");
        String question = question(alice);
        String root = root(question);
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> created = create(root, alice, requestId, null);
        assertThat(created).containsEntry("status", "WAITING_INPUT").containsEntry("currentRunId", null);
        assertThat(map(http.jsonBody(http.get(root + "/pending", alice)).get("request")))
                .containsEntry("requestId", requestId);
        http.assertErrorEnvelope(http.get(root + "/" + requestId, bob), 404, "A0606");
        http.assertErrorEnvelope(upload(root, requestId, bob,
                packet(AnswerTestData.manifest(requestId, null), true)), 404, "A0606");
        byte[] missing = packet(AnswerTestData.manifest(requestId, null), false);
        http.assertErrorEnvelope(upload(root, requestId, alice, missing), 400, "A0400");
        assertThat(http.jsonBody(http.get(root + "/" + requestId, alice)).get("run")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_run", Integer.class)).isZero();
        byte[] complete = packet(AnswerTestData.manifest(requestId, null), true);
        Map<String, Object> accepted = http.jsonBody(upload(root, requestId, alice, complete));
        Map<String, Object> request = map(accepted.get("request"));
        assertThat(request).containsEntry("status", "QUEUED");
        String fileRef = (String) request.get("snapshotFileRef");
        assertThat(fileRef).isNotNull();
        assertThat(map(accepted.get("run"))).containsEntry("status", "QUEUED");
        assertThat(http.jsonBody(http.get(root + "/pending", alice)).get("request")).isNull();
        assertThat(http.jsonBody(upload(root, requestId, alice, complete)).get("request"))
                .isEqualTo(accepted.get("request"));
        http.assertErrorEnvelope(upload(root, requestId, alice,
                packet(AnswerTestData.manifest(requestId, null), true, true)), 409, "A0454");
        MultiAgentModels.Snapshot snapshot = snapshots.load(accountId("v2_alice"), requestId);
        assertThat(snapshot.images().get(0).bytes()).isEqualTo(AnswerTestData.png(32));
        assertThat(snapshot.strokeIds()).containsExactly("s-1");
        assertThat(snapshot.hasHandwriting()).isTrue();
        assertThat(snapshot.ocrText()).isNull();
        assertThat(snapshot.strokeLocationsStatus()).isEqualTo(MultiAgentModels.StrokeLocationsStatus.COMPLETE);
        assertThat(snapshot.strokeLocations()).containsExactly(new MultiAgentModels.StrokeLocation(
                "s-1", new MultiAgentModels.WorldBounds(-3, -3, 5, 5), List.of(0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_run", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_source_version WHERE file_ref = ?",
                Integer.class, fileRef)).isEqualTo(1);
    }

    @Test
    void waitingCancelNeedsExplicitNullTargetAndPreventsLaterUpload() throws Exception {
        String alice = account("v2_alice");
        String question = question(alice);
        String root = root(question);
        String requestId = UUID.randomUUID().toString();
        create(root, alice, requestId, null);
        http.assertErrorEnvelope(action(root + "/" + requestId + "/cancel", alice, null,
                Map.of()), 400, "A0400");
        Map<String, Object> cancelled = http.jsonBody(action(root + "/" + requestId + "/cancel", alice, null,
                "{\"expectedRunId\":null}"));
        assertThat(map(cancelled.get("request"))).containsEntry("status", "CANCELLED")
                .containsEntry("currentRunId", null);
        http.assertErrorEnvelope(upload(root, requestId, alice,
                packet(AnswerTestData.manifest(requestId, null), true)), 409, "A0455");
        assertThat(http.jsonBody(http.get(root + "/pending", alice)).get("request")).isNull();
        String newerId = UUID.randomUUID().toString();
        create(root, alice, newerId, null);
        Map<String, Object> page = http.jsonBody(http.get(root + "?limit=1", alice));
        assertThat(list(page.get("items"))).hasSize(1);
        assertThat(list(page.get("items")).get(0)).containsEntry("requestId", newerId);
        String cursor = (String) page.get("nextCursor");
        assertThat(cursor).isNotNull();
        Map<String, Object> older = http.jsonBody(http.get(root + "?limit=1&cursor=" + cursor, alice));
        assertThat(list(older.get("items")).get(0)).containsEntry("requestId", requestId);
        http.assertErrorEnvelope(http.get(root(question(alice)) + "?cursor=" + cursor, alice),
                400, "A0400");
    }

    @Test
    void retryAndReuseAreExplicitAndOldCancelCannotReachNewRun() throws Exception {
        String alice = account("v2_alice");
        String question = question(alice);
        String root = root(question);
        String firstId = UUID.randomUUID().toString();
        create(root, alice, firstId, null);
        Map<String, Object> first = map(http.jsonBody(upload(root, firstId, alice,
                packet(AnswerTestData.manifest(firstId, null), true))).get("request"));
        String originalRun = (String) first.get("currentRunId");
        long owner = accountId("v2_alice");
        long revision = ledger.execution(owner, originalRun).revision();
        assertThat(ledger.fail(owner, originalRun, revision, "MODEL_UNAVAILABLE")).isTrue();
        String retryKey = UUID.randomUUID().toString();
        Map<String, Object> retried = http.jsonBody(action(root + "/" + firstId + "/retry", alice, retryKey,
                Map.of("targetRunId", originalRun)));
        String nextRun = (String) map(retried.get("request")).get("currentRunId");
        assertThat(nextRun).isNotEqualTo(originalRun);
        assertThat(map(retried.get("request"))).containsEntry("snapshotFileRef", first.get("snapshotFileRef"));
        assertThat(list(retried.get("runs"))).hasSize(2);
        assertThat(map(http.jsonBody(action(root + "/" + firstId + "/retry", alice, retryKey,
                Map.of("targetRunId", originalRun))).get("request")))
                .containsEntry("currentRunId", nextRun);
        http.assertErrorEnvelope(action(root + "/" + firstId + "/cancel", alice, null,
                Map.of("expectedRunId", originalRun)), 409, "A0455");
        assertThat(http.jsonBody(action(root + "/" + firstId + "/cancel", alice, null,
                Map.of("expectedRunId", nextRun))).get("request"))
                .isInstanceOf(Map.class);
        // P1 无执行器：测试显式关闭取消中的 run，再创建同题复用请求。
        ledger.reconcileDeadlines(java.time.Instant.now().plusSeconds(60), 100);
        String followupId = UUID.randomUUID().toString();
        Map<String, Object> followup = create(root, alice, followupId,
                Map.of("kind", "REUSE_REQUEST", "sourceRequestId", firstId));
        assertThat(followup).containsEntry("status", "QUEUED")
                .containsEntry("snapshotFileRef", first.get("snapshotFileRef"))
                .containsEntry("snapshotOriginRequestId", firstId);
        assertThat(snapshots.load(owner, followupId).images().get(0).bytes())
                .isEqualTo(AnswerTestData.png(32));
    }

    private Map<String, Object> create(String root, String token, String requestId,
            Map<String, Object> snapshotInput) throws Exception {
        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        body.put("requestId", requestId);
        body.put("prompt", "提示下一步");
        if (snapshotInput != null) {
            body.put("snapshotInput", snapshotInput);
        }
        HttpResponse<String> response = action(root, token, requestId, body);
        assertThat(response.statusCode()).isEqualTo(200);
        return http.jsonBody(response);
    }

    private HttpResponse<String> action(String path, String token, String key, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(http.uri(path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        String payload = body instanceof String literal ? literal : json.writeValueAsString(body);
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(payload)));
    }

    private HttpResponse<String> upload(String root, String requestId, String token, byte[] body) throws Exception {
        return http.send(HttpRequest.newBuilder(http.uri(root + "/" + requestId + "/snapshot"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    private byte[] packet(AnswerModels.SaveManifest manifest, boolean includeRaster) throws Exception {
        return packet(manifest, includeRaster, false);
    }

    private byte[] packet(AnswerModels.SaveManifest manifest, boolean includeRaster,
            boolean differentJson) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String manifestJson = json.writeValueAsString(manifest);
        part(body, "manifest", "application/json", (differentJson ? " " + manifestJson : manifestJson)
                .getBytes(StandardCharsets.UTF_8));
        part(body, "chunk_0000", "image/png", AnswerTestData.png(32));
        if (includeRaster) {
            part(body, "question_0000", "image/png", AnswerTestData.png(16));
        }
        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private static void part(ByteArrayOutputStream body, String name, String mediaType, byte[] bytes)
            throws IOException {
        String header = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + name + "\"\r\nContent-Type: " + mediaType + "\r\n\r\n";
        body.write(header.getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
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
        HttpResponse<String> response = http.post(QUESTIONS, json.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "subject", "OTHER",
                "bodyMarkdown", AnswerTestData.BODY)), token);
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(response).get("questionId");
    }

    private static String root(String question) {
        return "/api/v2/learning/questions/" + question + "/tutor-requests";
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("betterlearning-v2-http-it-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object source) {
        return (Map<String, Object>) source;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object source) {
        return (List<Map<String, Object>>) source;
    }
}
