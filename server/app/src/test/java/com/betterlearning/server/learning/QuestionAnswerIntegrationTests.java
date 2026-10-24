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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class QuestionAnswerIntegrationTests {
    private static final String ROOT = "/api/v1/learning/questions/";
    private static final String PASSWORD = "Learn-Pass123";
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-m2-integration-secret".getBytes(StandardCharsets.UTF_8));
    private static final Path FILE_ROOT = temporaryRoot();

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "m2-test", "m2-client");
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
    void savesBothPngGroupsAndRestoresFullDocumentWithAccountIsolation() throws Exception {
        String alice = account("alice_01");
        String bob = account("bob_02");
        String question = question(alice);
        assertThat(http.jsonBody(http.get(ROOT + question + "/answer", alice)))
                .containsEntry("state", "NOT_STARTED").containsEntry("fileRef", null);
        String saveRequestId = UUID.randomUUID().toString();
        byte[] payload = multipart(AnswerTestData.manifest(saveRequestId, null), true);
        HttpResponse<String> saved = save(alice, question, payload);
        assertThat(saved.statusCode()).isEqualTo(200);
        String ref = (String) http.jsonBody(saved).get("fileRef");
        Map<String, Object> detail = http.jsonBody(http.get(ROOT + question + "/answer", alice));
        assertThat(detail).containsEntry("fileRef", ref).containsEntry("state", "IN_PROGRESS");
        Map<String, Object> manifest = map(detail.get("manifest"));
        assertThat(manifest).containsEntry("saveRequestId", saveRequestId).containsEntry("baseFileRef", null);
        assertThat(map(manifest.get("questionSnapshot"))).containsEntry("bodyMarkdown", AnswerTestData.BODY);
        assertThat(map(manifest.get("canvas"))).containsEntry("originX", -8.0);
        Map<String, Object> layout = map(map(manifest.get("document")).get("questionLayout"));
        String compositeUrl = (String) maps(manifest.get("blocks")).get(0).get("url");
        String rasterUrl = (String) maps(layout.get("rasterBlocks")).get(0).get("url");
        assertThat(download(compositeUrl, alice).body()).isEqualTo(AnswerTestData.png(32));
        assertThat(download(rasterUrl, alice).body()).isEqualTo(AnswerTestData.png(16));
        http.assertErrorEnvelope(http.get(ROOT + question + "/answer", bob), 404, "A0606");
        http.assertErrorEnvelope(http.get(compositeUrl, bob), 404, "A0606");
        http.assertErrorEnvelope(http.get(rasterUrl, null), 401, "A0200");
        assertThat(http.jsonBody(http.get(ROOT + question, alice))).containsEntry("answerState", "IN_PROGRESS");
        Map<String, Object> page = http.jsonBody(http.get("/api/v1/learning/questions", alice));
        assertThat(maps(page.get("items")).get(0)).containsEntry("answerState", "IN_PROGRESS");
        assertThat(http.jsonBody(save(alice, question, payload)).get("fileRef")).isEqualTo(ref);
        byte[] reused = multipart(AnswerTestData.manifest(saveRequestId, ref), true);
        http.assertErrorEnvelope(save(alice, question, reused), 409, "A0454");
        byte[] incomplete = multipart(AnswerTestData.manifest(UUID.randomUUID().toString(), ref), false);
        http.assertErrorEnvelope(save(alice, question, incomplete), 400, "A0400");
        assertThat(http.jsonBody(http.get(ROOT + question + "/answer", alice))).containsEntry("fileRef", ref);
    }

    @Test
    void missingBlockFailsWithoutPublishingAndCannotCompleteUnsavedQuestion() throws Exception {
        String token = account("alice_01");
        String question = question(token);
        http.assertErrorEnvelope(state(token, question, "COMPLETED", UUID.randomUUID().toString()), 409, "A0452");
        byte[] payload = multipart(AnswerTestData.manifest(UUID.randomUUID().toString(), null), false);
        HttpResponse<String> failed = save(token, question, payload);
        http.assertErrorEnvelope(failed, 400, "A0400");
        assertThat(http.jsonBody(failed)).doesNotContainKey("fileRef");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_answer", Long.class)).isZero();
        assertThat(http.jsonBody(http.get(ROOT + question + "/answer", token)))
                .containsEntry("state", "NOT_STARTED");
    }

    @Test
    void completesContinuesAndOnlyReplacesMatchingCurrentReference() throws Exception {
        String token = account("alice_01");
        String question = question(token);
        String request = UUID.randomUUID().toString();
        byte[] first = multipart(AnswerTestData.manifest(request, null), true);
        String ref = (String) http.jsonBody(save(token, question, first)).get("fileRef");
        Map<String, Object> completed = http.jsonBody(state(token, question, "COMPLETED", ref));
        assertThat(completed.get("completedAt")).isNotNull();
        assertThat(http.jsonBody(state(token, question, "COMPLETED", ref)).get("completedAt"))
                .isEqualTo(completed.get("completedAt"));
        assertThat(http.jsonBody(save(token, question, first))).containsEntry("state", "COMPLETED");
        String nextRequest = UUID.randomUUID().toString();
        byte[] next = multipart(AnswerTestData.manifest(nextRequest, ref), true);
        http.assertErrorEnvelope(save(token, question, next), 409, "A0453");
        assertThat(http.jsonBody(state(token, question, "IN_PROGRESS", ref)))
                .containsEntry("completedAt", null).containsEntry("fileRef", ref);
        HttpResponse<String> second = save(token, question, next);
        assertThat(second.statusCode()).isEqualTo(200);
        String newer = (String) http.jsonBody(second).get("fileRef");
        assertThat(newer).isNotEqualTo(ref);
        assertThat(FILE_ROOT.resolve("objects").resolve(ref)).doesNotExist();
        http.assertErrorEnvelope(save(token, question, first), 409, "A0451");
        http.assertErrorEnvelope(state(token, question, "COMPLETED", ref), 409, "A0451");
        http.assertErrorEnvelope(http.get(ROOT + question + "/answer/files/" + ref + "/blocks/0", token), 404, "A0606");
        Map<String, Object> detail = http.jsonBody(http.get(ROOT + question + "/answer", token));
        assertThat(detail).containsEntry("fileRef", newer);
        assertThat(map(detail.get("manifest"))).containsEntry("saveRequestId", nextRequest)
                .containsEntry("baseFileRef", ref);
    }

    private HttpResponse<String> state(String token, String question, String state, String ref) throws Exception {
        return http.patch(ROOT + question + "/answer-state", json.writeValueAsString(Map.of(
                "state", state, "fileRef", ref)), token);
    }

    private HttpResponse<String> save(String token, String question, byte[] payload) throws Exception {
        return http.send(HttpRequest.newBuilder(http.uri(ROOT + question + "/answer"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=m2-boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)));
    }

    private HttpResponse<byte[]> download(String path, String token) throws Exception {
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(http.uri(path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        return response;
    }

    private byte[] multipart(AnswerModels.SaveManifest manifest, boolean includeRaster) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        part(bytes, "manifest", "application/json", json.writeValueAsBytes(manifest));
        part(bytes, "chunk_0000", "image/png", AnswerTestData.png(32));
        if (includeRaster) {
            part(bytes, "question_0000", "image/png", AnswerTestData.png(16));
        }
        bytes.write("--m2-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    private static void part(ByteArrayOutputStream bytes, String name, String mediaType, byte[] content) throws IOException {
        String header = "--m2-boundary\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + name + "\"\r\nContent-Type: " + mediaType + "\r\n\r\n";
        bytes.write(header.getBytes(StandardCharsets.UTF_8));
        bytes.write(content);
        bytes.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String account(String username) throws Exception {
        assertThat(http.post("/api/v1/users", TestData.registerJson(username, PASSWORD)).statusCode()).isEqualTo(201);
        return (String) http.jsonBody(http.post("/api/v1/sessions", TestData.loginJson(username, PASSWORD)))
                .get("accessToken");
    }

    private String question(String token) throws Exception {
        HttpResponse<String> response = http.post("/api/v1/learning/questions", json.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "subject", "OTHER", "bodyMarkdown", AnswerTestData.BODY)), token);
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(response).get("questionId");
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("betterlearning-m2-answer-it-");
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
}
