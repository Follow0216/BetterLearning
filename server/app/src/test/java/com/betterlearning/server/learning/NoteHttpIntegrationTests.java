package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.ArrayList;
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

    @Test
    void notebooksPreserveNotesAndDoNotPublishNewContentVersions() throws Exception {
        String alice = account("alice_01");
        String bob = account("bob_02");
        String notebookId = UUID.randomUUID().toString();
        HttpResponse<String> book = mutate("POST", "/api/v2/learning/notebooks", alice, notebookId,
                Map.of("name", "高数理解"));
        assertThat(book.statusCode()).isEqualTo(201);
        http.assertErrorEnvelope(mutate("POST", "/api/v2/learning/notebooks", alice, null,
                Map.of("name", "缺少键")), 400, "A0400");
        http.assertErrorEnvelope(mutate("POST", "/api/v2/learning/notebooks", alice, notebookId,
                Map.of("name", "其他名称")), 409, "A0400");
        String question = question("alice_01");
        Map<String, Object> context = context(notebookId, question, null);
        HttpResponse<String> created = mutate("POST", ROOT, alice, UUID.randomUUID().toString(),
                Map.of("title", "随题摘记", "bodyMarkdown", "自己的理解 $x^2$", "context", context));
        assertThat(created.statusCode()).isEqualTo(201);
        String noteId = (String) http.jsonBody(created).get("noteId");
        String sourceId = (String) http.jsonBody(created).get("sourceId");
        int versions = count("ma_source_version");
        int events = count("ma_outbox");
        Map<String, Object> unfile = new LinkedHashMap<>();
        unfile.put("notebookId", null);
        HttpResponse<String> filed = mutate("PUT", ROOT + "/" + noteId + "/context", alice,
                "\"context:" + noteId + ":1\"", unfile);
        assertThat(filed.statusCode()).isEqualTo(200);
        assertThat(count("ma_source_version")).isEqualTo(versions);
        assertThat(count("ma_outbox")).isEqualTo(events);
        http.assertErrorEnvelope(mutate("PUT", ROOT + "/" + noteId + "/context", alice,
                "\"context:" + noteId + ":1\"", Map.of("notebookId", notebookId)), 412, "A0400");
        http.assertErrorEnvelope(mutate("PUT", ROOT + "/" + noteId + "/context", bob,
                "\"context:" + noteId + ":2\"", Map.of("notebookId", notebookId)), 404, "A0606");
        assertThat(mutate("PUT", ROOT + "/" + noteId + "/context", alice,
                "\"context:" + noteId + ":2\"", Map.of("notebookId", notebookId)).statusCode()).isEqualTo(200);
        assertThat(write("PUT", ROOT + "/" + noteId, alice, created.headers().firstValue("ETag").orElseThrow(),
                "旧客户端更新", "旧客户端正文").statusCode()).isEqualTo(200);
        assertThat(noteContext(http.jsonBody(http.get(ROOT + "/" + noteId, alice))))
                .containsEntry("notebookId", notebookId).containsEntry("questionId", question);
        String bookPath = "/api/v2/learning/notebooks/" + notebookId;
        http.assertErrorEnvelope(mutate("DELETE", bookPath, alice, "\"notebook:" + notebookId + ":1\"", null), 400, "A0400");
        assertThat(mutate("PUT", bookPath, alice, "\"notebook:" + notebookId + ":1\"", Map.of("name", "高数复习")).statusCode()).isEqualTo(200);
        http.assertErrorEnvelope(mutate("DELETE", bookPath + "?confirm=true", alice,
                "\"notebook:" + notebookId + ":1\"", null), 412, "A0400");
        assertThat(mutate("DELETE", bookPath + "?confirm=true", alice,
                "\"notebook:" + notebookId + ":2\"", null).statusCode()).isEqualTo(204);
        Map<String, Object> kept = http.jsonBody(http.get(ROOT + "/" + noteId, alice));
        assertThat(kept).containsEntry("sourceId", sourceId).containsEntry("version", 2);
        assertThat(noteContext(kept)).containsEntry("notebookId", null).containsEntry("questionId", question);
    }

    @Test
    void searchIsLiteralAcrossAllNotesAndCursorIsBoundToAccountAndFilters() throws Exception {
        String alice = account("alice_01");
        String bob = account("bob_02");
        String notebookId = UUID.randomUUID().toString();
        mutate("POST", "/api/v2/learning/notebooks", alice, notebookId, Map.of("name", "关键步骤"));
        String question = question("alice_01");
        List<String> wanted = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Map<String, Object> note = http.jsonBody(mutate("POST", ROOT, alice, UUID.randomUUID().toString(),
                    Map.of("title", "早期记录" + index, "bodyMarkdown", "配方法 100%_\\ 原样关键词",
                    "context", context(notebookId, question, null))));
            wanted.add((String) note.get("noteId"));
        }
        for (int index = 0; index < 23; index++) {
            write("POST", ROOT, alice, UUID.randomUUID().toString(), "较新" + index, "配方法 100ab 普通正文");
        }
        write("POST", ROOT, bob, UUID.randomUUID().toString(), "他人笔记", "配方法 100%_\\");
        String filter = "?limit=1&q=" + URLEncoder.encode("100%_\\", StandardCharsets.UTF_8)
                + "&notebookId=" + notebookId + "&questionId=" + question;
        Map<String, Object> first = http.jsonBody(http.get(ROOT + filter, alice));
        assertThat(items(first)).hasSize(1);
        String cursor = (String) first.get("nextCursor");
        assertThat(cursor).isNotBlank();
        Map<String, Object> second = http.jsonBody(http.get(ROOT + filter + "&cursor=" + cursor, alice));
        assertThat(items(second).get(0).get("noteId")).isNotEqualTo(items(first).get(0).get("noteId"));
        Map<String, Object> all = http.jsonBody(http.get(ROOT + filter.replace("limit=1", "limit=20"), alice));
        assertThat(items(all).stream().map(item -> (String) item.get("noteId"))).containsExactlyInAnyOrderElementsOf(wanted);
        String plainFilter = "?limit=1&q=" + URLEncoder.encode("100%_\\", StandardCharsets.UTF_8);
        String plainCursor = (String) http.jsonBody(http.get(ROOT + plainFilter, alice)).get("nextCursor");
        http.assertErrorEnvelope(http.get(ROOT + plainFilter + "&cursor=" + plainCursor, bob), 400, "A0400");
        http.assertErrorEnvelope(http.get(ROOT + "?q=其他&cursor=" + plainCursor, alice), 400, "A0400");
        http.assertErrorEnvelope(http.get(ROOT + "?q=配方法&notebookId=unfiled&cursor=" + plainCursor, alice), 400, "A0400");
    }

    @Test
    void createRelationsAreAtomicAndOldKeyReplaysAfterNotebookDeletion() throws Exception {
        String alice = account("alice_01");
        String bob = account("bob_02");
        String question = question("alice_01");
        int notes = count("ma_note_request");
        http.assertErrorEnvelope(mutate("POST", ROOT, bob, UUID.randomUUID().toString(), Map.of("title", "越权",
                "bodyMarkdown", "不可创建", "context", context(null, question, null))), 404, "A0606");
        assertThat(count("ma_note_request")).isEqualTo(notes);
        String notebookId = UUID.randomUUID().toString();
        mutate("POST", "/api/v2/learning/notebooks", alice, notebookId, Map.of("name", "待归类"));
        String key = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of("title", "原稿", "bodyMarkdown", "原正文", "context", context(notebookId, question, null));
        Map<String, Object> created = http.jsonBody(mutate("POST", ROOT, alice, key, payload));
        mutate("DELETE", "/api/v2/learning/notebooks/" + notebookId + "?confirm=true", alice,
                "\"notebook:" + notebookId + ":1\"", null);
        Map<String, Object> replay = http.jsonBody(mutate("POST", ROOT, alice, key, payload));
        assertThat(replay).containsEntry("noteId", created.get("noteId"));
        assertThat(noteContext(replay)).containsEntry("notebookId", null);
        http.assertErrorEnvelope(mutate("POST", ROOT, alice, key, Map.of("title", "原稿", "bodyMarkdown", "原正文",
                "context", context(null, question, null))), 409, "A0400");
    }

    @Test
    void excerptsPinExactParagraphAndFixedVersionAndHideRevokedContent() throws Exception {
        String alice = account("alice_01");
        String bob = account("bob_02");
        String question = question("alice_01");
        long accountId = jdbc.queryForObject("SELECT account_id FROM app_user WHERE username='alice_01'", Long.class);
        String requestId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        String resultId = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        String original = "第一段说明。\n\n配方法关键步骤 $x^2+2x=(x+1)^2-1$。";
        String selected = original.split("\\n\\n")[1];
        jdbc.update("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,status,input_deadline_at) "
                + "VALUES (?,?,?,'提示','HINT','NEW_CAPTURE','SUCCEEDED',CURRENT_TIMESTAMP)", requestId, accountId, Long.parseLong(question));
        jdbc.update("INSERT INTO ma_run(run_id,request_id,account_id,run_no,status,queue_deadline_at,run_deadline_at) "
                + "VALUES (?,?,?,1,'SUCCEEDED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", runId, requestId, accountId);
        jdbc.update("INSERT INTO ma_result(result_id,run_id,mode,status,body_markdown) VALUES (?,?,'HINT','VERIFIED',?)", resultId, runId, original);
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,current_version) VALUES (?,?,'TUTOR_REPLY',?,'辅导',1)", sourceId, accountId, resultId);
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,body_markdown) VALUES (?,1,repeat('a',64),?)", sourceId, original);
        Map<String, Object> provenance = context(null, question, Map.of("resultId", resultId, "text", selected));
        String key = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of("title", "摘录理解", "bodyMarkdown", "我自己的理解", "context", provenance);
        Map<String, Object> created = http.jsonBody(mutate("POST", ROOT, alice, key, payload));
        assertThat(noteExcerpt(created)).containsEntry("sourceId", sourceId).containsEntry("version", 1)
                .containsEntry("text", selected).containsEntry("requestId", requestId).containsEntry("available", true);
        int count = count("ma_note_request");
        http.assertErrorEnvelope(mutate("POST", ROOT, alice, UUID.randomUUID().toString(), Map.of("title", "伪造",
                "bodyMarkdown", "我的理解", "context", context(null, question, Map.of("resultId", resultId, "text", "关键步骤")))), 400, "A0400");
        http.assertErrorEnvelope(mutate("POST", ROOT, bob, UUID.randomUUID().toString(), payload), 404, "A0606");
        assertThat(count("ma_note_request")).isEqualTo(count);
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,body_markdown) VALUES (?,2,repeat('b',64),'新版本')", sourceId);
        jdbc.update("UPDATE ma_source SET current_version=2 WHERE source_id=?", sourceId);
        String noteId = (String) created.get("noteId");
        assertThat(noteExcerpt(http.jsonBody(http.get(ROOT + "/" + noteId, alice)))).containsEntry("version", 1).containsEntry("text", selected);
        jdbc.update("UPDATE ma_source_version SET body_markdown=NULL,status='WITHDRAWN',purged_at=CURRENT_TIMESTAMP WHERE source_id=? AND version=1", sourceId);
        assertThat(noteExcerpt(http.jsonBody(http.get(ROOT + "/" + noteId, alice)))).containsEntry("available", false).containsEntry("text", null);
        assertThat(http.jsonBody(mutate("POST", ROOT, alice, key, payload))).containsEntry("noteId", noteId);
        jdbc.update("UPDATE ma_source SET status='DELETED' WHERE source_id=?", sourceId);
        assertThat(noteExcerpt(http.jsonBody(http.get(ROOT + "/" + noteId, alice)))).containsEntry("available", false);
        String turnId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tutor_turn(turn_id,account_id,question_id,request_id,prompt,status,file_ref,request_hash,answer_markdown) "
                + "VALUES (?,?,?,?,'原提问','SUCCEEDED',?,repeat('c',64),?)", turnId, accountId, Long.parseLong(question),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), original);
        Map<String, Object> legacy = http.jsonBody(mutate("POST", ROOT, alice, UUID.randomUUID().toString(),
                Map.of("title", "旧辅导摘记", "bodyMarkdown", "我的理解", "context", context(null, question, Map.of("turnId", turnId, "text", selected)))));
        assertThat(noteExcerpt(legacy)).containsEntry("turnId", turnId).containsEntry("text", selected).containsEntry("available", true);
        jdbc.update("UPDATE tutor_turn SET answer_markdown='已替换的文本' WHERE turn_id=?", turnId);
        assertThat(noteExcerpt(http.jsonBody(http.get(ROOT + "/" + legacy.get("noteId"), alice))))
                .containsEntry("available", false).containsEntry("text", null);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String question(String username) {
        long accountId = jdbc.queryForObject("SELECT account_id FROM app_user WHERE username=?", Long.class, username);
        return jdbc.queryForObject("INSERT INTO question(account_id,request_id,subject,body_markdown,summary) "
                + "VALUES (?,?,'HIGHER_MATHEMATICS','题面 $x^2$','题面') RETURNING question_id", Long.class,
                accountId, UUID.randomUUID().toString()).toString();
    }

    private Map<String, Object> context(String notebookId, String questionId, Map<String, Object> excerpt) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("notebookId", notebookId);
        context.put("questionId", questionId);
        context.put("excerpt", excerpt);
        return context;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> noteContext(Map<String, Object> note) {
        return (Map<String, Object>) note.get("context");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> noteExcerpt(Map<String, Object> note) {
        return (Map<String, Object>) noteContext(note).get("excerpt");
    }

    private HttpResponse<String> mutate(String method, String path, String token, String condition,
            Map<String, Object> payload) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(http.uri(path)).header("Authorization", "Bearer " + token);
        if (payload != null) {
            request.header("Content-Type", "application/json");
        }
        if (condition != null) {
            request.header("POST".equals(method) ? "Idempotency-Key" : "If-Match", condition);
        }
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8));
        return http.send(request);
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
