package com.betterlearning.server.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("security-it")
@Tag("integration")
class SessionLogoutIntegrationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.3")
            .asCompatibleSubstituteFor("postgres");

    private static final String PASSWORD = " Passw0rd! ";

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-security-integration-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("betterlearning")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.jwt.secret-base64", () -> SECRET);
        registry.add("app.jwt.issuer", () -> "betterlearning-test");
        registry.add("app.jwt.audience", () -> "betterlearning-client");
        registry.add("app.auth.login.ip-limit", () -> "1000");
        registry.add("app.auth.login.username-limit", () -> "1000");
        registry.add("app.auth.registration.ip-limit", () -> "1000");
        registry.add("app.cors.allowed-origins[0]", () -> "http://localhost:3000");
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    private String baseUrl;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void cleanState() {
        baseUrl = "http://127.0.0.1:" + port;
        jdbcTemplate.update("TRUNCATE TABLE app_user RESTART IDENTITY");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void deletesOnlyCurrentSessionAndKeepsOtherSessionUsable() throws Exception {
        register("alice_01");
        String firstToken = login("alice_01");
        String secondToken = login("alice_01");
        int tokenVersionBefore = tokenVersion("alice_01");

        HttpResponse<String> logout = delete("/api/v1/sessions/current", firstToken);

        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
        assertThat(logout.body()).doesNotContain(firstToken).doesNotContain("Bearer");
        assertThat(redisTemplate.hasKey(sessionKey(firstToken))).isFalse();
        assertThat(redisTemplate.hasKey(sessionKey(secondToken))).isTrue();

        assertErrorEnvelope(get("/api/v1/test/protected", firstToken), 401, "A0200");
        assertThat(get("/api/v1/test/protected", secondToken).statusCode()).isEqualTo(200);
        assertThat(tokenVersion("alice_01")).isEqualTo(tokenVersionBefore);
    }

    @Test
    void rejectsRepeatedLogoutWithRevokedToken() throws Exception {
        register("alice_01");
        String token = login("alice_01");

        assertThat(delete("/api/v1/sessions/current", token).statusCode()).isEqualTo(204);

        assertErrorEnvelope(delete("/api/v1/sessions/current", token), 401, "A0200");
        assertThat(redisTemplate.hasKey(sessionKey(token))).isFalse();
        assertThat(redisTemplate.keys("auth:session:*")).isEmpty();
    }

    @Test
    void rejectsLogoutWithoutToken() throws Exception {
        assertErrorEnvelope(delete("/api/v1/sessions/current", null), 401, "A0200");
    }

    private long register(String username) throws Exception {
        HttpResponse<String> response = post("/api/v1/users",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD
                + "\",\"confirmPassword\":\"" + PASSWORD + "\"}", null);
        assertThat(response.statusCode()).isEqualTo(201);
        return Long.parseLong((String) jsonBody(response).get("accountId"));
    }

    private String login(String username) throws Exception {
        HttpResponse<String> response = post("/api/v1/sessions",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}", null);
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) jsonBody(response).get("accessToken");
    }

    private int tokenVersion(String username) {
        Integer tokenVersion = jdbcTemplate.queryForObject(
                "SELECT token_version FROM app_user WHERE username = ?", Integer.class, username);
        assertThat(tokenVersion).isNotNull();
        return tokenVersion;
    }

    private String sessionKey(String token) {
        return "auth:session:" + jwtDecoder.decode(token).getId();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method("DELETE", HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return jsonMapper.readValue(response.body(), Map.class);
    }

    private void assertErrorEnvelope(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("code")).isEqualTo(code);
        assertThat((String) body.get("message")).isNotBlank();
        String traceId = (String) body.get("traceId");
        assertThat(traceId).isNotBlank();
        assertThat(response.headers().firstValue("X-Request-Id").orElse(null))
                .isEqualTo(traceId);
        assertThat(body).doesNotContainKeys("accessToken", "stackTrace", "exception");
    }
}
