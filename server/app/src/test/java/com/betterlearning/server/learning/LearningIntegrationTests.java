package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;
import com.betterlearning.server.support.TestHttpClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class LearningIntegrationTests {

    private static final String ROOT = "/api/v1/learning";
    private static final String PASSWORD = "Learn-Pass123";
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-m1-integration-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "m1-test", "m1-client");
        IntegrationProperties.registerRateLimits(registry);
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
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

    @Test
    void persistsExactMarkdownAndReturnsSamePreviewAcrossListAndDetail() throws Exception {
        String token = account("alice_01");
        String markdown = "# 求导\n求函数 **f(x)=x²** 的导数。\n";
        String requestId = UUID.randomUUID().toString();
        HttpResponse<String> confirmed = confirm(token, requestId, markdown);
        assertThat(confirmed.statusCode()).isEqualTo(201);
        Map<String, Object> detail = http.jsonBody(confirmed);
        assertThat(detail.get("questionId")).isInstanceOf(String.class);
        assertThat(detail.get("collectionId")).isEqualTo("inbox");
        assertThat(detail.get("bodyMarkdown")).isEqualTo(markdown);
        assertThat(detail.get("summary")).isEqualTo("求导 求函数 f(x)=x² 的导数。");
        assertThat(detail).doesNotContainKeys("accountId", "requestId", "title");
        HttpResponse<String> retried = confirm(token, requestId, markdown);
        assertThat(http.jsonBody(retried)).isEqualTo(detail);
        assertThat(http.jsonBody(http.get(ROOT + "/questions/" + detail.get("questionId"), token)))
                .isEqualTo(detail);
        Map<String, Object> page = http.jsonBody(http.get(ROOT + "/questions", token));
        Map<String, Object> item = items(page).get(0);
        assertThat(item.get("summary")).isEqualTo(detail.get("summary"));
        assertThat(item).doesNotContainKeys("bodyMarkdown", "accountId");
        assertThat(((Number) page.get("total")).longValue()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT body_markdown FROM question", String.class))
                .isEqualTo(markdown);
        assertThat(items(http.jsonBody(http.get(ROOT + "/collections", token))).get(0))
                .containsEntry("collectionId", "inbox").containsEntry("name", "待整理")
                .containsEntry("system", true).containsEntry("questionCount", 1);
    }

    @Test
    void isolatesAccountsAndRejectsConflictingRequestReuse() throws Exception {
        String alice = account("alice_01");
        String bob = account("bob_02");
        String requestId = UUID.randomUUID().toString();
        String aliceId = (String) http.jsonBody(confirm(alice, requestId, "Alice original")).get("questionId");
        HttpResponse<String> bobCreate = confirm(bob, requestId, "Bob original");
        assertThat(bobCreate.statusCode()).isEqualTo(201);
        assertThat(http.jsonBody(bobCreate).get("questionId")).isNotEqualTo(aliceId);
        http.assertErrorEnvelope(http.get(ROOT + "/questions/" + aliceId, bob), 404, "A0606");
        http.assertErrorEnvelope(confirm(alice, requestId, "changed"), 409, "A0445");
        assertThat(http.jsonBody(http.get(ROOT + "/questions/" + aliceId, alice)).get("bodyMarkdown"))
                .isEqualTo("Alice original");
        assertThat(((Number) http.jsonBody(http.get(ROOT + "/questions", bob)).get("total")).longValue())
                .isEqualTo(1L);
    }

    @Test
    void concurrentConfirmationCreatesExactlyOneRecord() throws Exception {
        String token = account("alice_01");
        String requestId = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 6; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return confirm(token, requestId, "concurrent body");
                }));
            }
            start.countDown();
            HashSet<Object> ids = new HashSet<>();
            for (Future<HttpResponse<String>> future : futures) {
                HttpResponse<String> response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(201);
                ids.add(http.jsonBody(response).get("questionId"));
            }
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_collection", Long.class)).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void pagesAndFiltersOwnedCollectionsWithoutLeakingOtherOwners() throws Exception {
        String alice = account("alice_01");
        String bob = account("bob_02");
        String first = (String) http.jsonBody(confirm(alice, UUID.randomUUID().toString(), "first"))
                .get("questionId");
        String second = (String) http.jsonBody(confirm(alice, UUID.randomUUID().toString(), "second"))
                .get("questionId");
        Map<String, Object> page = http.jsonBody(http.get(ROOT + "/questions?page=1&pageSize=1", alice));
        assertThat(items(page).get(0).get("questionId")).isEqualTo(second);
        assertThat(((Number) page.get("total")).longValue()).isEqualTo(2L);
        assertThat(items(http.jsonBody(http.get(ROOT + "/questions?page=3&pageSize=1", alice)))).isEmpty();
        Long owner = jdbc.queryForObject("SELECT account_id FROM app_user WHERE username='alice_01'", Long.class);
        Long bobId = jdbc.queryForObject("SELECT account_id FROM app_user WHERE username='bob_02'", Long.class);
        Long collection = jdbc.queryForObject("INSERT INTO question_collection(account_id,name) VALUES (?,?) "
                + "RETURNING collection_id", Long.class, owner, "自有题集");
        jdbc.update("UPDATE question SET collection_id=? WHERE question_id=?", collection, Long.parseLong(first));
        assertThat(items(http.jsonBody(http.get(ROOT + "/questions?collectionId=" + collection, alice))))
                .hasSize(1);
        assertThat(items(http.jsonBody(http.get(ROOT + "/questions?collectionId=inbox", alice))))
                .hasSize(1);
        http.assertErrorEnvelope(http.get(ROOT + "/questions?collectionId=" + collection, bob), 404, "A0606");
        assertThat(items(http.jsonBody(http.get(ROOT + "/collections", bob)))).hasSize(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO question(account_id,collection_id,request_id,subject,"
                + "body_markdown,summary) VALUES (?,?,?,?,?,?)", bobId, collection,
                UUID.randomUUID().toString(), "OTHER", "foreign", "foreign"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnauthenticatedInvalidAndUnknownFields() throws Exception {
        http.assertErrorEnvelope(http.get(ROOT + "/questions", null), 401, "A0200");
        http.assertErrorEnvelope(http.post(ROOT + "/questions", "{}"), 401, "A0200");
        String token = account("alice_01");
        http.assertErrorEnvelope(confirm(token, "bad-id", "body"), 400, "A0400");
        http.assertErrorEnvelope(confirm(token, UUID.randomUUID().toString(), "  "), 400, "A0400");
        http.assertErrorEnvelope(confirm(token, UUID.randomUUID().toString(), "x".repeat(20001)), 400, "A0400");
        http.assertErrorEnvelope(http.post(ROOT + "/questions", json.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "subject", "unknown", "bodyMarkdown", "body")), token),
                400, "A0400");
        http.assertErrorEnvelope(http.post(ROOT + "/questions", json.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "subject", "OTHER", "bodyMarkdown", "body",
                "accountId", "1")), token), 400, "A0427");
        for (String suffix : List.of("?page=0", "?pageSize=101", "/0", "/9223372036854775808")) {
            http.assertErrorEnvelope(http.get(ROOT + "/questions" + suffix, token), 400, "A0400");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question", Long.class)).isZero();
    }

    private String account(String username) throws Exception {
        assertThat(http.post("/api/v1/users", TestData.registerJson(username, PASSWORD)).statusCode()).isEqualTo(201);
        HttpResponse<String> login = http.post("/api/v1/sessions", TestData.loginJson(username, PASSWORD));
        assertThat(login.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(login).get("accessToken");
    }

    private HttpResponse<String> confirm(String token, String requestId, String markdown) throws Exception {
        return http.post(ROOT + "/questions", json.writeValueAsString(Map.of(
                "requestId", requestId, "subject", "HIGHER_MATHEMATICS", "bodyMarkdown", markdown)), token);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }
}
