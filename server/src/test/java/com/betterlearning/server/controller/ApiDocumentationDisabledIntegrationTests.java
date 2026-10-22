package com.betterlearning.server.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
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
@Tag("integration")
class ApiDocumentationDisabledIntegrationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.3")
            .asCompatibleSubstituteFor("postgres");

    private static final String PASSWORD = " Passw0rd! ";

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-security-integration-secret".getBytes(StandardCharsets.UTF_8));

    private static final List<String> DOCUMENTATION_PATHS = List.of(
            "/v3/api-docs",
            "/v3/api-docs/swagger-config",
            "/swagger-ui/index.html",
            "/swagger-ui.html");

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
        registry.add("APP_DOCS_ENABLED", () -> "false");
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
    void hidesDocumentationEndpointsForAnonymousRequests() throws Exception {
        for (String path : DOCUMENTATION_PATHS) {
            assertNotFound(get(path, null), path);
        }
    }

    @Test
    void hidesDocumentationEndpointsForAuthenticatedRequests() throws Exception {
        register("alice_01");
        String token = login("alice_01");

        assertThat(get("/api/v1/users/me", token).statusCode()).isEqualTo(200);

        for (String path : DOCUMENTATION_PATHS) {
            assertNotFound(get(path, token), path);
        }
    }

    private void assertNotFound(HttpResponse<String> response, String path) {
        assertThat(response.statusCode()).as(path).isEqualTo(404);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("code")).as(path).isEqualTo("A0606");
        assertThat(body.get("traceId")).as(path).isNotNull();
        assertThat(response.headers().firstValue("X-Request-Id").orElse(null))
                .as(path).isEqualTo(body.get("traceId"));
    }

    private void register(String username) throws Exception {
        HttpResponse<String> response = post("/api/v1/users",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD
                + "\",\"confirmPassword\":\"" + PASSWORD + "\"}");
        assertThat(response.statusCode()).isEqualTo(201);
    }

    private String login(String username) throws Exception {
        HttpResponse<String> response = post("/api/v1/sessions",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) jsonBody(response).get("accessToken");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return jsonMapper.readValue(response.body(), Map.class);
    }
}
