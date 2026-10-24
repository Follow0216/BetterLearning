package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.betterlearning.server.agent.model.AgentModelException;
import com.betterlearning.server.agent.service.OcrModel;
import com.betterlearning.server.agent.service.TutoringModel;
import com.betterlearning.server.learning.model.answer.AnswerModels;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TutorHttpIntegrationTests.Models.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class TutorHttpIntegrationTests {
    private static final String QUESTIONS = "/api/v1/learning/questions";
    private static final String PASSWORD = "Learn-Pass123";
    private static final Path FILE_ROOT = temporaryRoot();
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-m3-http-integration-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "m3-http-test", "m3-client");
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
    private ModelState state;

    private TestHttpClient http;

    @BeforeEach
    void clean() {
        http = new TestHttpClient(port, json);
        TestData.clean(jdbc, redis);
        state.tutorCalls.set(0);
        state.ocrCalls.set(0);
        state.failTutor.set(false);
        state.failOcr.set(false);
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
    void frozenTurnIsReadAcrossClientsAndOrdinarySaveDoesNotReplaceIt() throws Exception {
        String alice = account("alice_01");
        String bob = account("bob_02");
        String question = question(alice);
        String root = root(question);
        String request = UUID.randomUUID().toString();
        Map<String, Object> created = create(root, alice, request);
        String turn = (String) created.get("turnId");
        assertThat(create(root, alice, request).get("turnId")).isEqualTo(turn);
        assertThat(map(http.jsonBody(http.get(root + "/pending", alice)).get("turn")))
                .containsEntry("turnId", turn).containsEntry("fileRef", null);
        byte[] frozen = answer(AnswerTestData.manifest(turn, null), true);
        HttpResponse<String> accepted = send(root + "/" + turn + "/answer", alice, frozen);
        assertThat(accepted.statusCode()).isEqualTo(200);
        Map<String, Object> completed = http.jsonBody(accepted);
        assertThat(completed).containsEntry("status", "SUCCEEDED").containsEntry("answerMarkdown", "图像辅导结果");
        assertThat(state.tutorCalls.get()).isEqualTo(1);
        assertThat(state.imageCount.get()).isEqualTo(1);
        assertThat(http.jsonBody(send(root + "/" + turn + "/answer", alice, frozen)))
                .containsEntry("fileRef", completed.get("fileRef"));
        assertThat(state.tutorCalls.get()).isEqualTo(1);
        Map<String, Object> manifest = http.jsonBody(http.get(root + "/" + turn + "/answer", alice));
        assertThat(manifest).containsEntry("saveRequestId", turn).containsEntry("baseFileRef", null);
        assertThat(map(manifest.get("questionSnapshot"))).containsEntry("bodyMarkdown", AnswerTestData.BODY);
        String image = (String) maps(manifest.get("blocks")).get(0).get("url");
        String raster = (String) maps(map(map(manifest.get("document")).get("questionLayout")).get("rasterBlocks"))
                .get(0).get("url");
        byte[] ordinary = answer(AnswerTestData.manifest(UUID.randomUUID().toString(), null), true);
        assertThat(send(QUESTIONS + "/" + question + "/answer", alice, ordinary).statusCode()).isEqualTo(200);
        assertThat(download(image, alice)).isEqualTo(AnswerTestData.png(32));
        assertThat(download(raster, alice)).isEqualTo(AnswerTestData.png(16));
        HttpResponse<String> foreign = http.send(HttpRequest.newBuilder(http.uri(image))
                .header("Authorization", "Bearer " + bob).header("Accept", "image/png").GET());
        http.assertErrorEnvelope(foreign, 404, "A0606");
        assertThat(foreign.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        http.assertErrorEnvelope(http.get(root, bob), 404, "A0606");
        assertThat(maps(http.jsonBody(http.get(root, alice)).get("items"))).hasSize(1);
    }

    @Test
    void missingBlocksDoNotCallModelAndFailureRequiresManualSameTurnRetry() throws Exception {
        String token = account("alice_01");
        String question = question(token);
        String root = root(question);
        String turn = (String) create(root, token, UUID.randomUUID().toString()).get("turnId");
        String upload = root + "/" + turn + "/answer";
        byte[] missing = answer(AnswerTestData.manifest(turn, null), false);
        http.assertErrorEnvelope(send(upload, token, missing), 400, "A0400");
        assertThat(state.tutorCalls.get()).isZero();
        assertThat(http.jsonBody(http.get(root + "/" + turn, token))).containsEntry("fileRef", null);
        state.failTutor.set(true);
        byte[] complete = answer(AnswerTestData.manifest(turn, null), true);
        Map<String, Object> failure = http.jsonBody(send(upload, token, complete));
        assertThat(failure).containsEntry("status", "FAILED").containsEntry("failureCode", "MODEL_UNAVAILABLE");
        assertThat(failure.get("fileRef")).isNotNull();
        assertThat(http.jsonBody(send(upload, token, complete))).containsEntry("status", "FAILED");
        assertThat(state.tutorCalls.get()).isEqualTo(1);
        HttpResponse<String> retry = http.post(root + "/" + turn + "/retry", "{}", token);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(http.jsonBody(retry)).containsEntry("status", "SUCCEEDED").containsEntry("fileRef", failure.get("fileRef"));
        assertThat(state.tutorCalls.get()).isEqualTo(2);
        http.post(root + "/" + turn + "/retry", "{}", token);
        assertThat(state.tutorCalls.get()).isEqualTo(2);
        String nextTurn = (String) create(root, token, UUID.randomUUID().toString()).get("turnId");
        String other = question(token);
        http.assertErrorEnvelope(http.post(root(other), json.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "prompt", "另一题")), token), 409, "A0455");
        http.assertErrorEnvelope(http.post(root + "/" + nextTurn + "/cancel", "{\"accountId\":\"1\"}", token), 400, "A0427");
        HttpResponse<String> cancelled = http.post(root + "/" + nextTurn + "/cancel", "{}", token);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(http.jsonBody(cancelled)).containsEntry("failureCode", "TABLET_CANCELLED").containsEntry("fileRef", null);
    }

    @Test
    void ocrReturnsEditableMarkdownWithoutAutoInsertAndRejectsExtraParts() throws Exception {
        String token = account("alice_01");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, "image", "image/png", AnswerTestData.png(16));
        byte[] payload = end(body);
        HttpResponse<String> recognized = send("/api/v1/learning/ocr", token, payload);
        assertThat(recognized.statusCode()).isEqualTo(200);
        assertThat(http.jsonBody(recognized)).containsEntry("bodyMarkdown", AnswerTestData.BODY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question", Long.class)).isZero();
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        part(extra, "image", "image/png", AnswerTestData.png(16));
        part(extra, "extra", "image/png", AnswerTestData.png(16));
        http.assertErrorEnvelope(send("/api/v1/learning/ocr", token, end(extra)), 400, "A0400");
        assertThat(state.ocrCalls.get()).isEqualTo(1);
        state.failOcr.set(true);
        HttpResponse<String> unavailable = send("/api/v1/learning/ocr", token, payload);
        http.assertErrorEnvelope(unavailable, 503, "C0002");
        assertThat(unavailable.body()).doesNotContain("unit-dummy", "apiKey", "stackTrace");
        HttpResponse<String> confirmed = http.post(QUESTIONS, json.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "subject", "OTHER",
                "bodyMarkdown", http.jsonBody(recognized).get("bodyMarkdown"))), token);
        assertThat(confirmed.statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question", Long.class)).isEqualTo(1L);
    }

    private Map<String, Object> create(String root, String token, String request) throws Exception {
        HttpResponse<String> response = http.post(root, json.writeValueAsString(Map.of("requestId", request, "prompt", "解释解法")), token);
        assertThat(response.statusCode()).isEqualTo(200);
        return http.jsonBody(response);
    }

    private HttpResponse<String> send(String path, String token, byte[] bytes) throws Exception {
        return http.send(HttpRequest.newBuilder(http.uri(path)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=m3-boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)));
    }

    private byte[] download(String path, String token) throws Exception {
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(http.uri(path))
                .header("Authorization", "Bearer " + token).header("Accept", "image/png").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("image/png");
        return response.body();
    }

    private byte[] answer(AnswerModels.SaveManifest manifest, boolean includeRaster) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, "manifest", "application/json", json.writeValueAsBytes(manifest));
        part(body, "chunk_0000", "image/png", AnswerTestData.png(32));
        if (includeRaster) {
            part(body, "question_0000", "image/png", AnswerTestData.png(16));
        }
        return end(body);
    }

    private static void part(ByteArrayOutputStream body, String name, String mediaType, byte[] bytes) throws IOException {
        String header = "--m3-boundary\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + name + "\"\r\nContent-Type: " + mediaType + "\r\n\r\n";
        body.write(header.getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] end(ByteArrayOutputStream body) throws IOException {
        body.write("--m3-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private String account(String username) throws Exception {
        assertThat(http.post("/api/v1/users", TestData.registerJson(username, PASSWORD)).statusCode()).isEqualTo(201);
        return (String) http.jsonBody(http.post("/api/v1/sessions", TestData.loginJson(username, PASSWORD))).get("accessToken");
    }

    private String question(String token) throws Exception {
        HttpResponse<String> response = http.post(QUESTIONS, json.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "subject", "OTHER", "bodyMarkdown", AnswerTestData.BODY)), token);
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(response).get("questionId");
    }

    private static String root(String question) {
        return QUESTIONS + "/" + question + "/tutor-turns";
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("betterlearning-m3-http-it-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    static class ModelState {
        final AtomicInteger tutorCalls = new AtomicInteger();
        final AtomicInteger ocrCalls = new AtomicInteger();
        final AtomicBoolean failTutor = new AtomicBoolean();
        final AtomicBoolean failOcr = new AtomicBoolean();
        final AtomicInteger imageCount = new AtomicInteger();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Models {
        @Bean
        ModelState modelState() {
            return new ModelState();
        }

        @Bean
        @Primary
        TutoringModel unitTutoringModel(ModelState state) {
            return (prompt, snapshot) -> {
                state.tutorCalls.incrementAndGet();
                state.imageCount.set(snapshot.images().size());
                if (state.failTutor.getAndSet(false)) {
                    throw new AgentModelException(AgentModelException.Failure.MODEL_UNAVAILABLE);
                }
                return "图像辅导结果";
            };
        }

        @Bean
        @Primary
        OcrModel unitOcrModel(ModelState state) {
            return image -> {
                state.ocrCalls.incrementAndGet();
                if (state.failOcr.getAndSet(false)) {
                    throw new AgentModelException(AgentModelException.Failure.MODEL_NOT_CONFIGURED);
                }
                return AnswerTestData.BODY;
            };
        }
    }
}
