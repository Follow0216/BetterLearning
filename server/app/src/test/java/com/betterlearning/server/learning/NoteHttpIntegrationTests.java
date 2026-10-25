package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

/** 真实 HTTP、认证、Flyway 和 MyBatis；模型关闭，不计语义检索质量。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.agent.embedding.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class NoteHttpIntegrationTests {
    private static final String ROOT = "/api/v2/learning/notes";
    private static final String SOURCES = "/api/v2/learning/sources";
    private static final String PASSWORD = "Learn-Pass123";
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-p4-integration-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "p4-test", "p4-client");
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
    void authenticatedCrudHasStrongEtagsAndAccountIsolation() throws Exception {
        String alice = account("alice_01");
        String bob = account("bob_02");
        http.assertErrorEnvelope(http.get(ROOT, null), 401, "A0200");
        String key = UUID.randomUUID().toString();
        HttpResponse<String> created = write("POST", ROOT, alice, key, "初稿", "第一行\r\n第二行😀");
        assertThat(created.statusCode()).isEqualTo(201);
        Map<String, Object> note = http.jsonBody(created);
        assertThat(note).containsEntry("version", 1).containsEntry("bodyMarkdown", "第一行\n第二行😀");
        assertThat(note).doesNotContainKeys("accountId", "requestSha256");
        String noteId = (String) note.get("noteId");
        String sourceId = (String) note.get("sourceId");
        String etag = created.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).startsWith("\"").doesNotStartWith("W/");
        assertThat(http.jsonBody(write("POST", ROOT, alice, key, "初稿", "第一行\n第二行😀")))
                .containsEntry("noteId", noteId);
        http.assertErrorEnvelope(write("POST", ROOT, alice, key, "改标题", "第一行\n第二行😀"), 409, "A0400");
        http.assertErrorEnvelope(http.get(ROOT + "/" + noteId, bob), 404, "A0606");
        http.assertErrorEnvelope(http.get(SOURCES + "/" + sourceId, bob), 404, "A0606");
        HttpResponse<String> updated = write("PUT", ROOT + "/" + noteId, alice, etag, "更新", "配方法笔记");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(http.jsonBody(updated)).containsEntry("version", 2);
        String newEtag = updated.headers().firstValue("ETag").orElseThrow();
        http.assertErrorEnvelope(write("PUT", ROOT + "/" + noteId, alice, etag, "冲突", "旧编辑"), 412, "A0400");
        http.assertErrorEnvelope(write("PUT", ROOT + "/" + noteId, alice, null, "缺头", "正文"), 400, "A0400");
        HttpResponse<String> read = http.get(ROOT + "/" + noteId, alice);
        assertThat(read.headers().firstValue("ETag")).contains(newEtag);
        assertThat(read.headers().firstValue("Cache-Control")).contains("no-store");
        Map<String, Object> source = http.jsonBody(http.get(SOURCES + "/" + sourceId, alice));
        assertThat(source).containsOnlyKeys("sourceId", "type", "originId", "status", "createdAt",
                "currentVersion", "indexStatus", "answerState");
        assertThat(source).containsEntry("type", "NOTE").containsEntry("currentVersion", 2);
        assertThat(remove(noteId, alice, newEtag).statusCode()).isEqualTo(204);
        assertThat(remove(noteId, alice, newEtag).statusCode()).isEqualTo(204);
        http.assertErrorEnvelope(http.get(ROOT + "/" + noteId, alice), 404, "A0606");
        http.assertErrorEnvelope(http.get(SOURCES + "/" + sourceId, alice), 404, "A0606");
    }

    @Test
    void enforcesUtf8LimitAndBindsCursorsToAccountAndListKind() throws Exception {
        String alice = account("alice_01");
        String bob = account("bob_02");
        String boundary = "中".repeat(21845) + "x";
        assertThat(boundary.getBytes(StandardCharsets.UTF_8)).hasSize(65536);
        assertThat(write("POST", ROOT, alice, UUID.randomUUID().toString(), "边界", boundary).statusCode()).isEqualTo(201);
        http.assertErrorEnvelope(write("POST", ROOT, alice, UUID.randomUUID().toString(), "超限", boundary + "x"), 400, "A0400");
        for (int index = 0; index < 2; index++) {
            assertThat(write("POST", ROOT, alice, UUID.randomUUID().toString(), "笔记" + index, "正文").statusCode()).isEqualTo(201);
        }
        Map<String, Object> first = http.jsonBody(http.get(ROOT + "?limit=2", alice));
        assertThat(items(first)).hasSize(2);
        String cursor = (String) first.get("nextCursor");
        assertThat(cursor).isNotBlank();
        Map<String, Object> second = http.jsonBody(http.get(ROOT + "?limit=2&cursor=" + cursor, alice));
        assertThat(items(second)).hasSize(1);
        assertThat(second.get("nextCursor")).isNull();
        assertThat(items(second).get(0).get("noteId"))
                .isNotIn(items(first).stream().map(item -> item.get("noteId")).toList());
        http.assertErrorEnvelope(http.get(ROOT + "?cursor=" + cursor, bob), 400, "A0400");
        http.assertErrorEnvelope(http.get(SOURCES + "?cursor=" + cursor, alice), 400, "A0400");
        http.assertErrorEnvelope(http.get(ROOT + "?cursor=" + cursor + "x", alice), 400, "A0400");
        http.assertErrorEnvelope(http.get(ROOT + "?limit=101", alice), 400, "A0400");
        assertThat(items(http.jsonBody(http.get(SOURCES + "?type=NOTE", alice)))).hasSize(3);
        assertThat(items(http.jsonBody(http.get(SOURCES + "?type=SAVED_ANSWER", alice)))).isEmpty();
    }

    @Test
    void browserCanPreflightPutAndReadEtag() throws Exception {
        HttpResponse<String> preflight = http.send(HttpRequest.newBuilder(http.uri(ROOT + "/" + UUID.randomUUID()))
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "authorization,content-type,if-match")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Methods").orElseThrow()).contains("PUT");
        String token = account("alice_01");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(http.uri(ROOT))
                .header("Origin", "http://localhost:3000")
                .header("Authorization", "Bearer " + token).GET());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow()).contains("ETag");
    }

    private HttpResponse<String> write(String method, String path, String token, String condition,
            String title, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(http.uri(path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(
                json.writeValueAsString(Map.of("title", title, "bodyMarkdown", body)), StandardCharsets.UTF_8));
        if (condition != null) {
            request.header("POST".equals(method) ? "Idempotency-Key" : "If-Match", condition);
        }
        return http.send(request);
    }

    private HttpResponse<String> remove(String noteId, String token, String etag) throws Exception {
        return http.send(HttpRequest.newBuilder(http.uri(ROOT + "/" + noteId))
                .header("Authorization", "Bearer " + token).header("If-Match", etag).DELETE());
    }

    private String account(String username) throws Exception {
        HttpResponse<String> response = http.post("/api/v1/users", TestData.registerJson(username, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(http.post("/api/v1/sessions", TestData.loginJson(username, PASSWORD)))
                .get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }
}
