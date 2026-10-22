package com.betterlearning.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;

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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.CorsFilter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("security-it")
@Tag("integration")
class SecurityFilterChainIntegrationTests {

    private static final String PASSWORD = " Passw0rd! ";

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-security-integration-secret".getBytes(StandardCharsets.UTF_8));

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
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000",
                "http://localhost:3001");
    }

    @LocalServerPort
    private int port;

    private TestHttpClient http;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ServletContext servletContext;

    @BeforeEach
    void cleanState() {
        http = new TestHttpClient(port, jsonMapper);
        TestData.clean(jdbcTemplate, redisTemplate);
    }

    @Test
    void allowsAnonymousRegistrationAndLogin() throws Exception {
        HttpResponse<String> registration = post("/api/v1/users",
                registerJson("alice_01"));
        assertThat(registration.statusCode()).isEqualTo(201);

        HttpResponse<String> login = post("/api/v1/sessions", loginJson("alice_01", PASSWORD));
        assertThat(login.statusCode()).isEqualTo(201);
        assertThat((String) jsonBody(login).get("accessToken")).isNotBlank();
        assertThat(login.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void requiresAuthenticationForNonAnonymousMethodsOnAuthPaths() throws Exception {
        assertErrorEnvelope(get("/api/v1/users", null), 401, "A0200");
        assertErrorEnvelope(get("/api/v1/sessions", null), 401, "A0200");
    }

    @Test
    void ignoresSessionCookies() throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(http.uri("/api/v1/test/protected"))
                .header("Cookie", "JSESSIONID=attacker-controlled")
                .GET();
        HttpResponse<String> response = http.send(request);

        assertErrorEnvelope(response, 401, "A0200");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void rejectsUnknownFieldsOnAnonymousEndpoints() throws Exception {
        HttpResponse<String> registration = post("/api/v1/users",
                "{\"username\":\"bob_01\",\"password\":\"" + PASSWORD
                + "\",\"confirmPassword\":\"" + PASSWORD + "\",\"role\":\"ADMIN\"}");
        assertErrorEnvelope(registration, 400, "A0427");

        HttpResponse<String> login = post("/api/v1/sessions",
                "{\"username\":\"bob_01\",\"password\":\"" + PASSWORD
                + "\",\"tokenVersion\":0}");
        assertErrorEnvelope(login, 400, "A0427");
    }

    @Test
    void rejectsIgnoredFieldsOnAuthenticatedEndpoints() throws Exception {
        register("alice_01");
        String token = login("alice_01");

        HttpResponse<String> response = post("/api/v1/test/profile-probe",
                "{\"phoneProvided\":true}", token);

        assertErrorEnvelope(response, 400, "A0427");
    }

    @Test
    void returnsUnifiedUnauthorizedJsonWithoutToken() throws Exception {
        HttpResponse<String> response = get("/api/v1/test/protected", null);

        assertErrorEnvelope(response, 401, "A0200");
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse(""))
                .startsWith("Bearer");
        assertThat(response.body()).doesNotContain("invalid_token");
    }

    @Test
    void rejectsMalformedTokenWithUnifiedJson() throws Exception {
        HttpResponse<String> response = get("/api/v1/test/protected", "not-a-jwt");

        assertErrorEnvelope(response, 401, "A0200");
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse(""))
                .startsWith("Bearer").contains("invalid_token");
    }

    @Test
    void servesProtectedEndpointWithDatabaseBackedContextOnly() throws Exception {
        long accountId = register("alice_01");
        String token = login("alice_01");

        HttpResponse<String> response = get("/api/v1/test/protected", token);

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = jsonBody(response);
        assertThat(((Number) body.get("accountId")).longValue()).isEqualTo(accountId);
        assertThat(body.get("username")).isEqualTo("alice_01");
        assertThat(body.get("role")).isEqualTo("USER");
        assertThat(((Number) body.get("tokenVersion")).intValue()).isZero();
        assertThat(body.get("jwtId")).isEqualTo(jwtDecoder.decode(token).getId());
        assertThat(body.get("hasSession")).isEqualTo(false);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.body()).doesNotContain("JSESSIONID").doesNotContain(token)
                .doesNotContain("password");
    }

    @Test
    void forbidsRegularUserFromAdminPath() throws Exception {
        register("alice_01");
        String token = login("alice_01");

        HttpResponse<String> response = get("/api/v1/admin/test/ping", token);

        assertErrorEnvelope(response, 403, "A0300");
        assertThat(response.body()).doesNotContain(token).doesNotContain("JSESSIONID");
    }

    @Test
    void usesPromotedDatabaseRoleForExistingToken() throws Exception {
        register("alice_01");
        String token = login("alice_01");
        jdbcTemplate.update("UPDATE app_user SET role = 'ADMIN' WHERE username = ?", "alice_01");

        HttpResponse<String> response = get("/api/v1/admin/test/ping", token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(jsonBody(response).get("role")).isEqualTo("ADMIN");
    }

    @Test
    void appliesDemotedDatabaseRoleToExistingToken() throws Exception {
        register("alice_01");
        String token = login("alice_01");
        jdbcTemplate.update("UPDATE app_user SET role = 'ADMIN' WHERE username = ?", "alice_01");
        assertThat(get("/api/v1/admin/test/ping", token).statusCode()).isEqualTo(200);

        jdbcTemplate.update("UPDATE app_user SET role = 'USER' WHERE username = ?", "alice_01");

        assertErrorEnvelope(get("/api/v1/admin/test/ping", token), 403, "A0300");
        HttpResponse<String> protectedResponse = get("/api/v1/test/protected", token);
        assertThat(protectedResponse.statusCode()).isEqualTo(200);
        assertThat(jsonBody(protectedResponse).get("role")).isEqualTo("USER");
    }

    @Test
    void rejectsSessionOwnedByAnotherAccount() throws Exception {
        register("alice_01");
        register("bob_01");
        String token = login("alice_01");
        String key = sessionKey(token);
        redisTemplate.opsForValue().set(key, "999999", Duration.ofMinutes(10));

        HttpResponse<String> response = get("/api/v1/test/protected", token);

        assertErrorEnvelope(response, 401, "A0200");
    }

    @Test
    void rejectsMissingSession() throws Exception {
        register("alice_01");
        String token = login("alice_01");
        redisTemplate.delete(sessionKey(token));

        HttpResponse<String> response = get("/api/v1/test/protected", token);

        assertErrorEnvelope(response, 401, "A0200");
    }

    @Test
    void rejectsTokenVersionMismatch() throws Exception {
        register("alice_01");
        String token = login("alice_01");
        jdbcTemplate.update(
                "UPDATE app_user SET token_version = token_version + 1 WHERE username = ?",
                "alice_01");

        HttpResponse<String> response = get("/api/v1/test/protected", token);

        assertErrorEnvelope(response, 401, "A0200");
    }

    @Test
    void rejectsTokenWhoseAccountNoLongerExists() throws Exception {
        register("alice_01");
        String token = login("alice_01");
        jdbcTemplate.update("DELETE FROM app_user WHERE username = ?", "alice_01");

        HttpResponse<String> response = get("/api/v1/test/protected", token);

        assertErrorEnvelope(response, 401, "A0200");
    }

    @Test
    void appliesUsernameLengthLimitAfterTrimmingAndNormalization() throws Exception {
        String normalized = "a".repeat(31) + "_";

        HttpResponse<String> registration = post("/api/v1/users",
                registerJson("  " + normalized + "  "));
        assertThat(registration.statusCode()).isEqualTo(201);
        assertThat((String) jsonBody(registration).get("username")).isEqualTo(normalized);

        HttpResponse<String> login = post("/api/v1/sessions",
                loginJson("  " + normalized + "  ", PASSWORD));
        assertThat(login.statusCode()).isEqualTo(201);
        assertThat((String) jsonBody(login).get("accessToken")).isNotBlank();

        HttpResponse<String> tooLong = post("/api/v1/users",
                registerJson("a".repeat(33)));
        assertErrorEnvelope(tooLong, 400, "A0400");
    }

    @Test
    void returnsNotFoundForDisabledDocumentationPaths() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs", null);

        assertErrorEnvelope(response, 404, "A0606");
    }

    @Test
    void appliesCorsOnlyForConfiguredOrigins() throws Exception {
        HttpResponse<String> allowed = preflight("http://localhost:3000");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
                .isEqualTo("http://localhost:3000");
        assertThat(allowed.headers().allValues("Access-Control-Allow-Credentials")).isEmpty();
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Methods").orElse(""))
                .contains("GET");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Headers").orElse("")
                .toLowerCase()).contains("authorization").contains("content-type");

        HttpResponse<String> allowedActual = getWithOrigin("/api/v1/test/protected",
                "http://localhost:3000");
        assertThat(allowedActual.statusCode()).isEqualTo(401);
        assertThat(allowedActual.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
                .isEqualTo("http://localhost:3000");

        HttpResponse<String> denied = preflight("http://evil.example");
        assertErrorEnvelope(denied, 403, "A0300");
        assertThat(denied.headers().allValues("Access-Control-Allow-Origin")).isEmpty();
        assertThat(redisTemplate.keys("auth:session:*")).isEmpty();
    }

    @Test
    void rejectsDeniedCorsActualRequestBeforeAuthentication() throws Exception {
        HttpResponse<String> response = getWithOrigin("/api/v1/test/protected",
                "http://evil.example");

        assertErrorEnvelope(response, 403, "A0300");
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.headers().allValues("WWW-Authenticate")).isEmpty();
    }

    @Test
    void registersTraceIdFilterExactlyOnceAndKeepsCorsInsideSecurityChain() {
        long registrations = servletContext.getFilterRegistrations().values().stream()
                .map(FilterRegistration::getClassName)
                .filter(TraceIdFilter.class.getName()::equals)
                .count();
        assertThat(registrations).isEqualTo(1);
        long corsRegistrations = servletContext.getFilterRegistrations().values().stream()
                .map(FilterRegistration::getClassName)
                .filter(CorsFilter.class.getName()::equals)
                .count();
        assertThat(corsRegistrations).isZero();
        assertThat(servletContext.getFilterRegistrations())
                .containsKey("springSecurityFilterChain");
    }

    private long register(String username) throws Exception {
        HttpResponse<String> response = post("/api/v1/users", registerJson(username));
        assertThat(response.statusCode()).isEqualTo(201);
        return Long.parseLong((String) jsonBody(response).get("accountId"));
    }

    private String login(String username) throws Exception {
        HttpResponse<String> response = post("/api/v1/sessions", loginJson(username, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) jsonBody(response).get("accessToken");
    }

    private String sessionKey(String token) {
        return "auth:session:" + jwtDecoder.decode(token).getId();
    }

    private String registerJson(String username) {
        return TestData.registerJson(username, PASSWORD);
    }

    private String loginJson(String username, String password) {
        return TestData.loginJson(username, password);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.get(path, token);
    }

    private HttpResponse<String> getWithOrigin(String path, String origin) throws Exception {
        return http.send(HttpRequest.newBuilder(http.uri(path))
                .header("Origin", origin)
                .GET());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return post(path, body, null);
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return http.post(path, body, token);
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                http.uri("/api/v1/test/protected"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization,content-type");
        return http.send(request);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return http.jsonBody(response);
    }

    private void assertErrorEnvelope(HttpResponse<String> response, int status, String code) {
        http.assertErrorEnvelope(response, status, code);
    }
}
