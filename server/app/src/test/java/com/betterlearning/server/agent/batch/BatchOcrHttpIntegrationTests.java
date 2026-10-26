package com.betterlearning.server.agent.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import com.betterlearning.server.agent.model.AgentModelException.Failure;
import com.betterlearning.server.agent.model.BatchOcrModels.Question;
import com.betterlearning.server.agent.model.BatchOcrModels.Result;
import com.betterlearning.server.agent.model.BatchOcrModels.Source;
import com.betterlearning.server.agent.model.BatchOcrModels.Tile;
import com.betterlearning.server.agent.model.BatchOcrProcessingException;
import com.betterlearning.server.agent.service.BatchOcrEngine;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

/** 小样本真实 HTTP/数据库身份测试；engine 为 fake，不连接真实模型。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class BatchOcrHttpIntegrationTests {
    private static final String ROOT = "/api/v1/learning/ocr/batches/";
    private static final String PASSWORD = "Learn-Pass123";
    private static final Path FILE_ROOT = temporaryRoot();
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-batch-ocr-http-test-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "batch-ocr-test", "batch-ocr-client");
        IntegrationProperties.registerRateLimits(registry);
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

    @MockitoBean
    private BatchOcrEngine engine;

    private TestHttpClient http;

    @BeforeEach
    void clean() {
        http = new TestHttpClient(port, json);
        TestData.clean(jdbc, redis);
    }

    @AfterAll
    static void removeFiles() throws Exception {
        try (var paths = Files.walk(FILE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void replaysWithoutExecutionAndIsolatesAccountsWhileCancellationFencesLateResults() throws Exception {
        String alice = account("batch_alice");
        String bob = account("batch_bob");
        String requestId = UUID.randomUUID().toString();
        byte[] image = png(16);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Result partial = partial();
        when(engine.process(anyList(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            BatchOcrEngine.Observer observer = invocation.getArgument(1);
            observer.progress(1, 2, "OCR");
            observer.partial(partial);
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test model was not released");
            }
            return new Result(List.of(new Question("item-0", "晚到题目", List.of("image-0"),
                    List.of("tile-0"), false, List.of())), partial.sources(), partial.tiles(), List.of(), false);
        });
        try {
            http.assertErrorEnvelope(http.get(ROOT + requestId, null), 401, "A0200");
            assertThat(upload(requestId, alice, image).statusCode()).isEqualTo(200);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(http.jsonBody(upload(requestId, alice, image))).containsEntry("status", "RUNNING");
            assertThat(calls.get()).isEqualTo(1);
            http.assertErrorEnvelope(upload(requestId, alice, png(17)), 409, "A0445");
            http.assertErrorEnvelope(upload(UUID.randomUUID().toString(), alice, image), 409, "A0455");
            http.assertErrorEnvelope(http.get(ROOT + requestId, bob), 404, "A0606");
            http.assertErrorEnvelope(http.delete(ROOT + requestId, bob), 404, "A0606");
            Map<String, Object> running = http.jsonBody(http.get(ROOT + requestId, alice));
            assertThat(running).containsEntry("completedTiles", 1).containsEntry("totalTiles", 2);
            assertThat(map(running.get("result")).get("tiles")).asList().hasSize(1);
            assertThat(http.jsonBody(http.delete(ROOT + requestId, alice)))
                    .containsEntry("status", "CANCELLED");
            release.countDown();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "SELECT worker_owner FROM ocr_batch WHERE request_id=?", String.class, requestId)).isNull());
            assertThat(http.jsonBody(http.get(ROOT + requestId, alice))).containsEntry("status", "CANCELLED");
            assertThat(http.jsonBody(upload(requestId, alice, image))).containsEntry("status", "CANCELLED");
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void freshForeignWorkerSurvivesQueriesButExpiredLeaseIsUnknownAndModelFailureKeepsPartial() throws Exception {
        String alice = account("batch_alice");
        long accountId = jdbc.queryForObject("SELECT account_id FROM app_user WHERE username=?",
                Long.class, "batch_alice");
        String orphanId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ocr_batch(account_id,request_id,fingerprint,status,stage,worker_owner,lease_until) "
                + "VALUES(?,?,?,'RUNNING','OCR',?,CURRENT_TIMESTAMP + INTERVAL '5 minutes')",
                accountId, orphanId, "a".repeat(64), UUID.randomUUID().toString());
        assertThat(http.jsonBody(http.get(ROOT + orphanId, alice))).containsEntry("status", "RUNNING");
        jdbc.update("UPDATE ocr_batch SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second' "
                + "WHERE account_id=? AND request_id=?", accountId, orphanId);
        assertThat(http.jsonBody(http.get(ROOT + orphanId, alice))).containsEntry("status", "UNKNOWN");
        assertThat(http.jsonBody(http.get(ROOT + orphanId, alice))).containsEntry("status", "UNKNOWN");
        AtomicInteger calls = new AtomicInteger();
        when(engine.process(anyList(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            BatchOcrEngine.Observer observer = invocation.getArgument(1);
            observer.progress(1, 2, "OCR");
            observer.partial(partial());
            throw new BatchOcrProcessingException(Failure.MODEL_UNAVAILABLE, partial());
        });
        String requestId = UUID.randomUUID().toString();
        byte[] image = png(16);
        assertThat(upload(requestId, alice, image).statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                http.jsonBody(http.get(ROOT + requestId, alice))).containsEntry("status", "FAILED"));
        Map<String, Object> failed = http.jsonBody(http.get(ROOT + requestId, alice));
        assertThat(failed).containsEntry("errorCode", "MODEL_UNAVAILABLE");
        assertThat(map(failed.get("result")).get("tiles")).asList().hasSize(1);
        assertThat(http.jsonBody(upload(requestId, alice, image))).containsEntry("status", "FAILED");
        assertThat(calls.get()).isEqualTo(1);
    }

    private String account(String username) throws Exception {
        assertThat(http.post("/api/v1/users", TestData.registerJson(username, PASSWORD)).statusCode()).isEqualTo(201);
        return (String) http.jsonBody(http.post("/api/v1/sessions", TestData.loginJson(username, PASSWORD)))
                .get("accessToken");
    }

    private HttpResponse<String> upload(String requestId, String token, byte[] image) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--batch-boundary\r\nContent-Disposition: form-data; name=\"image0\"; filename=\"image.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(image);
        body.write("\r\n--batch-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(http.uri(ROOT + requestId))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=batch-boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    }

    private static Result partial() {
        return new Result(List.of(), List.of(new Source("image-0", 0, 16, 16)),
                List.of(new Tile("tile-0", "image-0", 0, 0, 16, 16, false, "已识别原片文本")),
                List.of("等待剩余原片"), true);
    }

    private static byte[] png(int size) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("bl-batch-ocr-http-");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
