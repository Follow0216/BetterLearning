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

import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.multiagent.MultiAgentModels.CreateRequest;
import com.betterlearning.server.multiagent.MultiAgentModels.Mode;
import com.betterlearning.server.multiagent.MultiAgentModels.Request;
import com.betterlearning.server.multiagent.MultiAgentModels.SnapshotBinding;
import com.betterlearning.server.multiagent.MultiAgentModels.SnapshotInput;
import com.betterlearning.server.multiagent.TutorLedger;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;
import com.betterlearning.server.support.TestHttpClient;

import jakarta.servlet.http.Part;

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

/** 真实 HTTP、数据库与持久文件验证辅导合成图预算；不调用真实模型。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class TutorBudgetHttpIntegrationTests {
    private static final String QUESTIONS = "/api/v1/learning/questions";
    private static final String PASSWORD = "Learn-Pass123";
    private static final String BOUNDARY = "v2-budget-boundary";
    private static final String BUDGET_MESSAGE = "完整作答超过当前辅导图片32MiB预算，原作答已保留";
    private static final Path FILE_ROOT = temporaryRoot();
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-v2-budget-http-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "v2-budget-http", "v2-budget-client");
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
    private AnswerFileStore files;

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
    void overBudgetNewCaptureKeepsWaitingAndAcceptsLaterValidPackage() throws Exception {
        String token = account("budget_alice");
        String questionId = question(token);
        String requestId = UUID.randomUUID().toString();
        String root = root(questionId);
        create(root, token, requestId, null);
        TutorBudgetTestData.Fixture oversized = fixture(questionId, requestId,
                List.of(12 * TutorBudgetTestData.MIB, 12 * TutorBudgetTestData.MIB,
                12 * TutorBudgetTestData.MIB), TutorBudgetTestData.smallPng().length);
        long objectsBefore = entries(FILE_ROOT.resolve("objects"));
        int outboxBefore = count("ma_outbox");

        assertBudgetError(upload(root, requestId, token, oversized));
        assertWaitingWithoutPaidWork(root, requestId, token);
        assertThat(entries(FILE_ROOT.resolve("objects"))).isEqualTo(objectsBefore);
        assertThat(entries(FILE_ROOT.resolve("staging"))).isZero();
        assertThat(count("ma_outbox")).isEqualTo(outboxBefore);

        int small = TutorBudgetTestData.smallPng().length;
        TutorBudgetTestData.Fixture valid = fixture(questionId, requestId,
                List.of(small, small, small), small);
        HttpResponse<String> accepted = upload(root, requestId, token, valid);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(map(http.jsonBody(accepted).get("request"))).containsEntry("status", "QUEUED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_run", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox WHERE kind = 'RUN_START'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_attempt", Integer.class)).isZero();
    }

    @Test
    void failedLargeUploadCanCancelAndRasterMayTakeTotalBeyondCompositeBudget() throws Exception {
        String token = account("budget_alice");
        String questionId = question(token);
        String root = root(questionId);
        String waitingId = UUID.randomUUID().toString();
        create(root, token, waitingId, null);
        TutorBudgetTestData.Fixture oversized = fixture(questionId, waitingId,
                List.of(12 * TutorBudgetTestData.MIB, 12 * TutorBudgetTestData.MIB,
                12 * TutorBudgetTestData.MIB), TutorBudgetTestData.smallPng().length);
        assertBudgetError(upload(root, waitingId, token, oversized));
        assertWaitingWithoutPaidWork(root, waitingId, token);
        HttpResponse<String> cancelled = action(root + "/" + waitingId + "/cancel", token, null,
                "{\"expectedRunId\":null}");
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(map(http.jsonBody(cancelled).get("request"))).containsEntry("status", "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_run", Integer.class)).isZero();

        String acceptedId = UUID.randomUUID().toString();
        create(root, token, acceptedId, null);
        TutorBudgetTestData.Fixture largeRaster = fixture(questionId, acceptedId,
                List.of(16 * TutorBudgetTestData.MIB, 16 * TutorBudgetTestData.MIB),
                TutorBudgetTestData.MIB);
        assertThat(largeRaster.compositePngs().stream().mapToLong(bytes -> bytes.length).sum())
                .isEqualTo(32L * TutorBudgetTestData.MIB);
        assertThat(largeRaster.rasterPng().length).isEqualTo(TutorBudgetTestData.MIB);
        HttpResponse<String> accepted = upload(root, acceptedId, token, largeRaster);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(map(http.jsonBody(accepted).get("request"))).containsEntry("status", "QUEUED");
    }

    @Test
    void reuseAndExplicitRetryRejectPreviouslyStoredLargeSnapshotBeforeLedgerWrites() throws Exception {
        String token = account("budget_alice");
        long owner = accountId("budget_alice");
        String questionId = question(token);
        String root = root(questionId);
        String originalId = UUID.randomUUID().toString();
        TutorBudgetTestData.Fixture legacy = fixture(questionId, originalId,
                List.of(12 * TutorBudgetTestData.MIB, 12 * TutorBudgetTestData.MIB,
                12 * TutorBudgetTestData.MIB), TutorBudgetTestData.smallPng().length);
        ledger.create(owner, questionId, originalId,
                new CreateRequest(originalId, "提示", Mode.HINT, new SnapshotInput("NEW_CAPTURE", null)));
        AnswerFileStore.Candidate candidate = files.save(legacy.manifest(), legacy.snapshot(), legacy.parts());
        Request bound = ledger.bindSnapshot(owner, questionId, originalId,
                new SnapshotBinding(candidate.fileRef(), candidate.requestHash(), null, 0));
        assertThat(files.manifest(candidate.fileRef()).source()).isEqualTo(legacy.manifest());
        long revision = ledger.execution(owner, bound.currentRunId()).revision();
        assertThat(ledger.fail(owner, bound.currentRunId(), revision, "MODEL_UNAVAILABLE")).isTrue();
        int requestsBefore = count("ma_request");
        int runsBefore = count("ma_run");
        int eventsBefore = count("ma_outbox");
        int startsBefore = runStarts();

        String followupId = UUID.randomUUID().toString();
        HttpResponse<String> reuse = action(root, token, followupId,
                Map.of("requestId", followupId, "prompt", "继续提示",
                "snapshotInput", Map.of("kind", "REUSE_REQUEST", "sourceRequestId", originalId)));
        assertBudgetError(reuse);
        HttpResponse<String> retry = action(root + "/" + originalId + "/retry", token,
                UUID.randomUUID().toString(), Map.of("targetRunId", bound.currentRunId()));
        assertBudgetError(retry);

        assertThat(count("ma_request")).isEqualTo(requestsBefore);
        assertThat(count("ma_run")).isEqualTo(runsBefore);
        assertThat(count("ma_outbox")).isEqualTo(eventsBefore);
        assertThat(runStarts()).isEqualTo(startsBefore);
        assertThat(count("ma_attempt")).isZero();
        assertThat(ledger.get(owner, questionId, originalId).currentRunId()).isEqualTo(bound.currentRunId());
        assertThat(Files.isRegularFile(FILE_ROOT.resolve("objects").resolve(candidate.fileRef())
                .resolve("manifest.json"))).isTrue();
        assertThat(files.manifest(candidate.fileRef()).source()).isEqualTo(legacy.manifest());
        for (int index = 0; index < legacy.compositePngs().size(); index++) {
            byte[] stored = files.block(candidate.fileRef(), legacy.manifest().blocks().get(index).partName());
            assertThat(stored.length).isEqualTo(legacy.compositePngs().get(index).length);
            assertThat(AnswerManifestValidator.sha256(stored))
                    .isEqualTo(legacy.manifest().blocks().get(index).sha256());
        }
        assertThat(ledger.execution(owner, bound.currentRunId()).modelCalls()).isZero();
        assertThat(ledger.providerCallsInFlight(owner)).isZero();

        HttpResponse<String> replay = upload(root, originalId, token, legacy);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(map(http.jsonBody(replay).get("request")))
                .containsEntry("currentRunId", bound.currentRunId())
                .containsEntry("status", "FAILED");
        assertThat(count("ma_run")).isEqualTo(runsBefore);
        assertThat(count("ma_outbox")).isEqualTo(eventsBefore);
        assertThat(runStarts()).isEqualTo(startsBefore);
        assertThat(ledger.execution(owner, bound.currentRunId()).modelCalls()).isZero();
        assertThat(ledger.providerCallsInFlight(owner)).isZero();
    }

    private TutorBudgetTestData.Fixture fixture(String questionId, String requestId,
            List<Integer> compositeBytes, int rasterBytes) throws Exception {
        return TutorBudgetTestData.fixture(questionId, requestId, compositeBytes, rasterBytes, json);
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

    private HttpResponse<String> upload(String root, String requestId, String token,
            TutorBudgetTestData.Fixture fixture) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Part part : fixture.parts()) {
            String header = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + part.getName()
                    + "\"; filename=\"" + part.getName() + "\"\r\nContent-Type: " + part.getContentType()
                    + "\r\n\r\n";
            body.write(header.getBytes(StandardCharsets.UTF_8));
            part.getInputStream().transferTo(body);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(http.uri(root + "/" + requestId + "/snapshot"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    }

    private void assertBudgetError(HttpResponse<String> response) {
        http.assertErrorEnvelope(response, 413, "A0430");
        assertThat(http.jsonBody(response)).containsEntry("message", BUDGET_MESSAGE);
    }

    private void assertWaitingWithoutPaidWork(String root, String requestId, String token) throws Exception {
        assertThat(map(http.jsonBody(http.get(root + "/" + requestId, token)).get("request")))
                .containsEntry("status", "WAITING_INPUT")
                .containsEntry("snapshotFileRef", null)
                .containsEntry("currentRunId", null);
        assertThat(map(http.jsonBody(http.get(root + "/pending", token)).get("request")))
                .containsEntry("requestId", requestId);
        assertThat(count("ma_run")).isZero();
        assertThat(runStarts()).isZero();
        assertThat(count("ma_attempt")).isZero();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static long entries(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.count();
        }
    }

    private int runStarts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox WHERE kind = 'RUN_START'", Integer.class);
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
                "bodyMarkdown", TutorBudgetTestData.BODY)), token);
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(response).get("questionId");
    }

    private static String root(String questionId) {
        return "/api/v2/learning/questions/" + questionId + "/tutor-requests";
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("betterlearning-v2-budget-http-it-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object source) {
        return (Map<String, Object>) source;
    }
}
