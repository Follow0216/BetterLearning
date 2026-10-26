package com.betterlearning.server.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
class ApiDocumentationEnabledIntegrationTests {


    private static final String PASSWORD = " Passw0rd! ";

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-security-integration-secret".getBytes(StandardCharsets.UTF_8));

    private static final Pattern SCRIPT_SOURCE = Pattern.compile("src=\"([^\"]+)\"");

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "betterlearning-test",
                "betterlearning-client");
        IntegrationProperties.registerRateLimits(registry);
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
        registry.add("APP_DOCS_ENABLED", () -> "true");
    }

    @LocalServerPort
    private int port;

    private TestHttpClient http;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void cleanState() {
        http = new TestHttpClient(port, jsonMapper);
        TestData.clean(jdbcTemplate, redisTemplate);
    }

    @Test
    void servesSwaggerUiAndStaticAssets() throws Exception {
        HttpResponse<String> ui = get("/swagger-ui/index.html");

        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("Swagger UI");

        Matcher matcher = SCRIPT_SOURCE.matcher(ui.body());
        assertThat(matcher.find()).isTrue();
        String scriptPath = resolveAssetPath(matcher.group(1));
        HttpResponse<String> script = get(scriptPath);

        assertThat(script.statusCode()).isEqualTo(200);
        assertThat(script.body()).isNotBlank();
    }

    @Test
    void servesOpenApiDocumentAndSwaggerConfig() throws Exception {
        HttpResponse<String> docsResponse = get("/v3/api-docs");
        assertThat(docsResponse.statusCode()).isEqualTo(200);

        HttpResponse<String> configResponse = get("/v3/api-docs/swagger-config");
        assertThat(configResponse.statusCode()).isEqualTo(200);
        Map<String, Object> config = jsonBody(configResponse);
        assertThat(config.get("url")).isEqualTo("/v3/api-docs");
    }

    @Test
    void documentsExactlyTheLearningAndNoteWorkspaceOperations() throws Exception {
        Map<String, Object> docs = jsonBody(get("/v3/api-docs"));
        assertThat(docs.get("openapi")).isEqualTo("3.1.0");
        Map<String, Object> paths = asMap(docs.get("paths"));

        Map<String, List<String>> expectedOperations = Map.ofEntries(
                Map.entry("/api/v1/users", List.of("post")),
                Map.entry("/api/v1/sessions", List.of("post")),
                Map.entry("/api/v1/sessions/current", List.of("delete")),
                Map.entry("/api/v1/users/me", List.of("get", "patch")),
                Map.entry("/api/v1/users/me/password", List.of("patch")),
                Map.entry("/api/v1/learning/questions", List.of("get", "post")),
                Map.entry("/api/v1/learning/questions/{questionId}", List.of("get")),
                Map.entry("/api/v1/learning/collections", List.of("get", "post")),
                Map.entry("/api/v1/admin/session", List.of("get")),
                Map.entry("/api/v1/learning/collections/{collectionId}", List.of("patch", "post")),
                Map.entry("/api/v1/learning/questions/{questionId}/collection", List.of("patch", "post")),
                Map.entry("/api/v1/learning/questions/{questionId}/answer", List.of("get", "post")),
                Map.entry("/api/v1/learning/questions/{questionId}/answer-state", List.of("patch", "post")),
                Map.entry("/api/v1/learning/questions/{questionId}/answer/files/{fileRef}/blocks/{index}", List.of("get")),
                Map.entry("/api/v1/learning/questions/{questionId}/answer/files/{fileRef}/question-blocks/{index}", List.of("get")),
                Map.entry("/api/v1/learning/questions/{questionId}/tutor-turns", List.of("get", "post")),
                Map.entry("/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}", List.of("get")),
                Map.entry("/api/v1/learning/questions/{questionId}/tutor-turns/pending", List.of("get")),
                Map.entry("/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/answer", List.of("get", "post")),
                Map.entry("/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/retry", List.of("post")),
                Map.entry("/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/cancel", List.of("post")),
                Map.entry("/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/answer/files/{fileRef}/blocks/{index}", List.of("get")),
                Map.entry("/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/answer/files/{fileRef}/question-blocks/{index}", List.of("get")),
                Map.entry("/api/v1/learning/ocr", List.of("post")),
                Map.entry("/api/v2/learning/questions/{questionId}/tutor-requests", List.of("get", "post")),
                Map.entry("/api/v2/learning/questions/{questionId}/tutor-requests/pending", List.of("get")),
                Map.entry("/api/v2/learning/questions/{questionId}/tutor-requests/{requestId}", List.of("get")),
                Map.entry("/api/v2/learning/questions/{questionId}/tutor-requests/{requestId}/snapshot", List.of("post")),
                Map.entry("/api/v2/learning/questions/{questionId}/tutor-requests/{requestId}/cancel", List.of("post")),
                Map.entry("/api/v2/learning/questions/{questionId}/tutor-requests/{requestId}/retry", List.of("post")),
                Map.entry("/api/v2/learning/notes", List.of("get", "post")),
                Map.entry("/api/v2/learning/notes/{noteId}", List.of("get", "put", "delete")),
                Map.entry("/api/v2/learning/notes/{noteId}/context", List.of("put")),
                Map.entry("/api/v2/learning/notebooks", List.of("get", "post")),
                Map.entry("/api/v2/learning/notebooks/{notebookId}", List.of("put", "delete")),
                Map.entry("/api/v2/learning/sources", List.of("get")),
                Map.entry("/api/v2/learning/sources/{sourceId}", List.of("get")),
                Map.entry("/api/v2/learning/citations/{citationId}/resolve", List.of("get")),
                Map.entry("/api/v2/learning/citations/{citationId}/assets/{blockIndex}", List.of("get")));
        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(expectedOperations.keySet());
        assertThat(paths.keySet()).noneMatch(path -> path.startsWith("/api/v1/test")
                || (path.startsWith("/api/v1/admin") && !path.equals("/api/v1/admin/session")));
        for (Map.Entry<String, List<String>> expected : expectedOperations.entrySet()) {
            assertThat(asMap(paths.get(expected.getKey())).keySet())
                    .containsExactlyInAnyOrderElementsOf(expected.getValue());
        }

        assertOperation(docs, "/api/v1/users", "post", "201");
        assertOperation(docs, "/api/v1/sessions", "post", "201");
        assertOperation(docs, "/api/v1/sessions/current", "delete", "204");
        assertOperation(docs, "/api/v1/users/me", "get", "200");
        assertOperation(docs, "/api/v1/users/me", "patch", "200");
        assertOperation(docs, "/api/v1/users/me/password", "patch", "204");
        for (String[] addedOperation : List.of(
                new String[] {"/api/v1/learning/questions", "post", "201"},
                new String[] {"/api/v1/learning/questions", "get", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}", "get", "200"},
                new String[] {"/api/v1/learning/collections", "get", "200"},
                new String[] {"/api/v1/admin/session", "get", "200"},
                new String[] {"/api/v1/learning/collections", "post", "201"},
                new String[] {"/api/v1/learning/collections/{collectionId}", "patch", "200"},
                new String[] {"/api/v1/learning/collections/{collectionId}", "post", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/collection", "patch", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/collection", "post", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer", "get", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer", "post", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer-state", "patch", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer-state", "post", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer/files/{fileRef}/blocks/{index}", "get", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer/files/{fileRef}/question-blocks/{index}", "get", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns", "get", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns", "post", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}", "get", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/pending", "get", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/answer", "get", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/answer", "post", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/retry", "post", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/cancel", "post", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/answer/files/{fileRef}/blocks/{index}", "get", "200"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/answer/files/{fileRef}/question-blocks/{index}", "get", "200"},
                new String[] {"/api/v1/learning/ocr", "post", "200"})) {
            Map<String, Object> responses = asMap(operation(docs, addedOperation[0], addedOperation[1])
                    .get("responses"));
            assertThat(responses).containsKey(addedOperation[2]);
            assertThat(asMap(responses.get(addedOperation[2]))).containsKey("content");
        }
    }

    @Test
    void documentsP4StatusesAndRequiredConditionalHeaders() throws Exception {
        Map<String, Object> docs = jsonBody(get("/v3/api-docs"));
        for (String[] item : List.of(
                new String[] {"/api/v2/learning/notes", "get", "200"},
                new String[] {"/api/v2/learning/notes", "post", "201"},
                new String[] {"/api/v2/learning/notes/{noteId}", "get", "200"},
                new String[] {"/api/v2/learning/notes/{noteId}", "put", "200"},
                new String[] {"/api/v2/learning/notes/{noteId}", "delete", "204"},
                new String[] {"/api/v2/learning/notes/{noteId}/context", "put", "200"},
                new String[] {"/api/v2/learning/notebooks", "get", "200"},
                new String[] {"/api/v2/learning/notebooks", "post", "201"},
                new String[] {"/api/v2/learning/notebooks/{notebookId}", "put", "200"},
                new String[] {"/api/v2/learning/notebooks/{notebookId}", "delete", "204"},
                new String[] {"/api/v2/learning/sources", "get", "200"},
                new String[] {"/api/v2/learning/sources/{sourceId}", "get", "200"},
                new String[] {"/api/v2/learning/citations/{citationId}/resolve", "get", "200"},
                new String[] {"/api/v2/learning/citations/{citationId}/assets/{blockIndex}", "get", "200"})) {
            Map<String, Object> responses = asMap(operation(docs, item[0], item[1])
                    .get("responses"));
            assertThat(responses).as(item[0] + " " + item[1]).containsKey(item[2]);
            Map<String, Object> success = asMap(responses.get(item[2]));
            if ("204".equals(item[2])) {
                assertThat(success).doesNotContainKey("content");
            } else {
                assertThat(success).containsKey("content");
            }
        }
        assertRequiredHeader(docs, "/api/v2/learning/notes", "post", "Idempotency-Key");
        assertRequiredHeader(docs, "/api/v2/learning/notes/{noteId}", "put", "If-Match");
        assertRequiredHeader(docs, "/api/v2/learning/notes/{noteId}", "delete", "If-Match");
        assertRequiredHeader(docs, "/api/v2/learning/notes/{noteId}/context", "put", "If-Match");
        assertRequiredHeader(docs, "/api/v2/learning/notebooks", "post", "Idempotency-Key");
        assertRequiredHeader(docs, "/api/v2/learning/notebooks/{notebookId}", "put", "If-Match");
        assertRequiredHeader(docs, "/api/v2/learning/notebooks/{notebookId}", "delete", "If-Match");
    }

    @Test
    void requiresBearerOnlyForProtectedOperations() throws Exception {
        Map<String, Object> docs = jsonBody(get("/v3/api-docs"));

        Map<String, Object> scheme = asMap(
                asMap(asMap(docs.get("components")).get("securitySchemes")).get("bearerAuth"));
        assertThat(scheme.get("type")).isEqualTo("http");
        assertThat(scheme.get("scheme")).isEqualTo("bearer");
        assertThat(scheme.get("bearerFormat")).isEqualTo("JWT");
        assertThat(scheme.get("description").toString())
                .contains("Authorize").contains("只填写 JWT").contains("不要添加 Bearer 前缀");

        assertThat(asList(operation(docs, "/api/v1/users", "post").get("security")))
                .isNullOrEmpty();
        assertThat(asList(operation(docs, "/api/v1/sessions", "post").get("security")))
                .isNullOrEmpty();
        for (String[] protectedOperation : List.of(
                new String[] {"/api/v1/sessions/current", "delete"},
                new String[] {"/api/v1/users/me", "get"},
                new String[] {"/api/v1/users/me", "patch"},
                new String[] {"/api/v1/users/me/password", "patch"},
                new String[] {"/api/v1/learning/questions", "post"},
                new String[] {"/api/v1/learning/questions", "get"},
                new String[] {"/api/v1/learning/questions/{questionId}", "get"},
                new String[] {"/api/v1/learning/collections", "get"},
                new String[] {"/api/v1/admin/session", "get"},
                new String[] {"/api/v1/learning/collections", "post"},
                new String[] {"/api/v1/learning/collections/{collectionId}", "patch"},
                new String[] {"/api/v1/learning/collections/{collectionId}", "post"},
                new String[] {"/api/v1/learning/questions/{questionId}/collection", "patch"},
                new String[] {"/api/v1/learning/questions/{questionId}/collection", "post"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer", "get"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer", "post"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer-state", "patch"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer-state", "post"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer/files/{fileRef}/blocks/{index}", "get"},
                new String[] {"/api/v1/learning/questions/{questionId}/answer/files/{fileRef}/question-blocks/{index}", "get"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns", "get"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns", "post"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}", "get"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/pending", "get"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/answer", "get"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/answer", "post"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/retry", "post"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/cancel", "post"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/answer/files/{fileRef}/blocks/{index}", "get"},
                new String[] {"/api/v1/learning/questions/{questionId}/tutor-turns/{turnId}/answer/files/{fileRef}/question-blocks/{index}", "get"},
                new String[] {"/api/v1/learning/ocr", "post"},
                new String[] {"/api/v2/learning/notes", "get"},
                new String[] {"/api/v2/learning/notes", "post"},
                new String[] {"/api/v2/learning/notes/{noteId}", "get"},
                new String[] {"/api/v2/learning/notes/{noteId}", "put"},
                new String[] {"/api/v2/learning/notes/{noteId}", "delete"},
                new String[] {"/api/v2/learning/notes/{noteId}/context", "put"},
                new String[] {"/api/v2/learning/notebooks", "get"},
                new String[] {"/api/v2/learning/notebooks", "post"},
                new String[] {"/api/v2/learning/notebooks/{notebookId}", "put"},
                new String[] {"/api/v2/learning/notebooks/{notebookId}", "delete"},
                new String[] {"/api/v2/learning/sources", "get"},
                new String[] {"/api/v2/learning/sources/{sourceId}", "get"},
                new String[] {"/api/v2/learning/citations/{citationId}/resolve", "get"},
                new String[] {"/api/v2/learning/citations/{citationId}/assets/{blockIndex}", "get"})) {
            assertThat(asList(operation(docs, protectedOperation[0], protectedOperation[1])
                    .get("security")))
                    .anySatisfy(requirement ->
                    assertThat(asMap(requirement)).containsKey("bearerAuth"));
        }
    }

    @Test
    void documentsLoginResponseAndPasswordSchemas() throws Exception {
        Map<String, Object> docs = jsonBody(get("/v3/api-docs"));
        Map<String, Object> schemas = asMap(asMap(docs.get("components")).get("schemas"));

        Map<String, Object> loginResponse = asMap(asMap(operation(docs, "/api/v1/sessions", "post")
                .get("responses")).get("201"));
        assertThat(responseSchema(loginResponse)).isEqualTo("#/components/schemas/SessionVO");

        Map<String, Object> sessionProperties = asMap(asMap(schemas.get("SessionVO"))
                .get("properties"));
        assertThat(sessionProperties.keySet())
                .containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn", "user");
        assertThat(description(sessionProperties.get("expiresIn"))).contains("7200")
                .contains("不").contains("续期");

        Map<String, Object> registerProperties = asMap(asMap(schemas.get("RegisterUserRequest"))
                .get("properties"));
        assertPassword(registerProperties.get("password"), 8);
        assertPassword(registerProperties.get("confirmPassword"), 8);
        assertThat(description(registerProperties.get("password"))).contains("72");

        Map<String, Object> changePasswordProperties = asMap(asMap(schemas
                .get("ChangePasswordRequest")).get("properties"));
        assertPassword(changePasswordProperties.get("oldPassword"), 1);
        assertPassword(changePasswordProperties.get("newPassword"), 8);
        assertPassword(changePasswordProperties.get("confirmPassword"), 8);

        Map<String, Object> loginProperties = asMap(asMap(schemas.get("LoginRequest"))
                .get("properties"));
        assertPassword(loginProperties.get("password"), 1);
    }

    @Test
    void documentsFiveCharacterErrorCodesAndNewDescriptions() throws Exception {
        Map<String, Object> docs = jsonBody(get("/v3/api-docs"));
        Map<String, Object> schemas = asMap(asMap(docs.get("components")).get("schemas"));
        Map<String, Object> codeSchema = asMap(asMap(asMap(schemas.get("ApiErrorVO"))
                .get("properties")).get("code"));

        assertThat(codeSchema.get("pattern")).isEqualTo("^[ABC][0-9]{4}$");
        assertThat(codeSchema.get("example")).isEqualTo("A0400");
        assertThat(asList(codeSchema.get("enum"))).containsExactly(
                "A0400", "A0427", "A0200", "A0300", "A0606", "A0507", "A0428", "A0429",
                "A0111", "A0154", "A0155", "A0444", "A0445", "A0450", "A0451", "A0452", "A0453", "A0454", "A0430", "A0455", "A0456", "A0431", "C0002", "A0501", "C0001", "B0001");
        assertThat(description(codeSchema)).contains("5 位").contains("HTTP 状态码无关");

        Map<String, Object> registrationResponses = asMap(operation(docs, "/api/v1/users", "post")
                .get("responses"));
        assertThat(description(registrationResponses.get("400")))
                .contains("A0400").contains("A0427").doesNotContain("INVALID_");
        assertThat(description(registrationResponses.get("409"))).contains("A0111");
        assertThat(description(registrationResponses.get("429"))).contains("A0501");
        assertThat(description(registrationResponses.get("503"))).contains("C0001");

        Map<String, Object> profileResponses = asMap(operation(docs, "/api/v1/users/me", "patch")
                .get("responses"));
        assertThat(description(profileResponses.get("409")))
                .contains("A0154").contains("A0155");

        Map<String, Object> passwordResponses = asMap(operation(docs,
                "/api/v1/users/me/password", "patch").get("responses"));
        assertThat(description(passwordResponses.get("409"))).contains("A0444");
    }

    @Test
    void hidesInternalFieldsAndAuthenticationPrincipal() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");
        Map<String, Object> docs = jsonBody(response);
        Map<String, Object> schemas = asMap(asMap(docs.get("components")).get("schemas"));

        Map<String, Object> updateProperties = asMap(asMap(schemas.get("UpdateProfileRequest"))
                .get("properties"));
        assertThat(updateProperties.keySet()).containsExactlyInAnyOrder("phone", "email");
        assertThat(asMap(schemas.get("UpdateProfileRequest"))).doesNotContainKey("required");
        assertNullableString(updateProperties.get("phone"), "UpdateProfileRequest.phone");
        assertNullableString(updateProperties.get("email"), "UpdateProfileRequest.email");
        assertThat(response.body()).doesNotContain("phoneProvided")
                .doesNotContain("emailProvided")
                .doesNotContain("AuthenticatedAccount")
                .doesNotContain("jwtId");

        Map<String, Object> userProperties = asMap(asMap(schemas.get("UserVO")).get("properties"));
        assertThat(userProperties.keySet()).contains("accountId", "username", "phone", "email",
                "role", "createdAt", "updatedAt");
        assertThat(asMap(userProperties.get("accountId")).get("type")).isEqualTo("string");
        assertNullableString(userProperties.get("phone"), "UserVO.phone");
        assertNullableString(userProperties.get("email"), "UserVO.email");

        Map<String, Object> profileOperation = operation(docs, "/api/v1/users/me", "patch");
        assertThat(description(profileOperation.get("description")))
                .contains("不修改").contains("解绑").contains("400").contains("409");
        assertThat(profileOperation).doesNotContainKey("parameters");
    }

    @Test
    void forbidsAdditionalPropertiesOnAllRequestSchemas() throws Exception {
        Map<String, Object> docs = jsonBody(get("/v3/api-docs"));
        Map<String, Object> schemas = asMap(asMap(docs.get("components")).get("schemas"));

        for (String schemaName : List.of("RegisterUserRequest", "LoginRequest",
                "UpdateProfileRequest", "ChangePasswordRequest")) {
            assertThat(asMap(schemas.get(schemaName)).get("additionalProperties"))
                    .as(schemaName).isEqualTo(false);
        }
    }

    @Test
    void documentsNormalizedUsernameLengthWithoutRawMaxLength() throws Exception {
        Map<String, Object> docs = jsonBody(get("/v3/api-docs"));
        Map<String, Object> schemas = asMap(asMap(docs.get("components")).get("schemas"));

        Map<String, Object> registerUsername = asMap(asMap(asMap(schemas
                .get("RegisterUserRequest")).get("properties")).get("username"));
        assertThat(registerUsername.get("minLength")).isEqualTo(3);
        assertThat(registerUsername).doesNotContainKey("maxLength");
        assertThat(description(registerUsername)).contains("规范化后").contains("32");

        Map<String, Object> loginUsername = asMap(asMap(asMap(schemas
                .get("LoginRequest")).get("properties")).get("username"));
        assertThat(loginUsername.get("minLength")).isEqualTo(3);
        assertThat(loginUsername).doesNotContainKey("maxLength");
        assertThat(description(loginUsername)).contains("规范化后").contains("32");
    }

    private void assertOperation(Map<String, Object> docs, String path, String method,
            String successStatus) {
        Map<String, Object> operation = operation(docs, path, method);
        assertThat(description(operation.get("summary"))).containsPattern("[\\u4e00-\\u9fa5]");

        Map<String, Object> responses = asMap(operation.get("responses"));
        assertThat(responses).containsKey(successStatus);
        Map<String, Object> success = asMap(responses.get(successStatus));
        if ("204".equals(successStatus)) {
            assertThat(success).doesNotContainKey("content");
        } else {
            assertThat(success).containsKey("content");
        }
        for (String errorStatus : List.of("400", "401", "409", "429", "503")) {
            if (!responses.containsKey(errorStatus)) {
                continue;
            }
            Map<String, Object> error = asMap(responses.get(errorStatus));
            String code = description(error.get("description"));
            assertThat(code).containsPattern("[ABC][0-9]{4}|请求参数");
        }
    }

    private void assertRequiredHeader(Map<String, Object> docs, String path, String method,
            String headerName) {
        assertThat(asList(operation(docs, path, method).get("parameters")))
                .anySatisfy(parameter -> assertThat(asMap(parameter))
                .containsEntry("name", headerName)
                .containsEntry("in", "header")
                .containsEntry("required", true));
    }

    private Map<String, Object> operation(Map<String, Object> docs, String path, String method) {
        return asMap(asMap(asMap(docs.get("paths")).get(path)).get(method));
    }

    private String responseSchema(Map<String, Object> response) {
        Map<String, Object> content = asMap(response.get("content"));
        assertThat(content).isNotEmpty();
        Map<String, Object> media = asMap(content.values().iterator().next());
        return (String) asMap(media.get("schema")).get("$ref");
    }

    private static void assertPassword(Object schemaValue, int minLength) {
        Map<String, Object> schema = asMap(schemaValue);
        assertThat(schema.get("format")).isEqualTo("password");
        assertThat(schema.get("writeOnly")).isEqualTo(true);
        assertThat(schema.get("minLength")).isEqualTo(minLength);
        assertThat(schema.get("maxLength")).isEqualTo(64);
    }

    private static void assertNullableString(Object schemaValue, String field) {
        Map<String, Object> schema = asMap(schemaValue);
        Object type = schema.get("type");
        assertThat(type).as(field).isInstanceOf(List.class);
        assertThat(asList(type)).as(field).containsExactlyInAnyOrder("string", "null");
    }

    private static String description(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object description = map.get("description");
            return description == null ? "" : description.toString();
        }
        return value == null ? "" : value.toString();
    }

    private String resolveAssetPath(String source) {
        if (source.startsWith("./")) {
            return "/swagger-ui/" + source.substring(2);
        }
        return source.startsWith("/") ? source : "/swagger-ui/" + source;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.get(path, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return http.jsonBody(response);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }
}
