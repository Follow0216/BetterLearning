package com.betterlearning.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("security-it")
@Tag("integration")
class RedisOutageIntegrationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.3")
            .asCompatibleSubstituteFor("postgres");

    private static final String PASSWORD = " Passw0rd! ";

    private static final String SECRET = "betterlearning-security-integration-secret";

    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(300);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("betterlearning")
            .withUsername("test")
            .withPassword("test");

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2"))
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "300ms");
        registry.add("spring.data.redis.connect-timeout", () -> "300ms");
        registry.add("app.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(
                SECRET.getBytes(StandardCharsets.UTF_8)));
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
    private RedisConnectionFactory redisConnectionFactory;

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
    }

    @AfterAll
    static void stopContainers() {
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
        POSTGRES.stop();
    }

    @Test
    void returnsServiceUnavailableWhenOwnedRedisContainerStops() throws Exception {
        assertThat(redisConnectionFactory).isInstanceOf(LettuceConnectionFactory.class);
        assertThat(((LettuceConnectionFactory) redisConnectionFactory)
                .getClientConfiguration().getCommandTimeout())
                .as("测试专用 Redis 命令超时必须生效，避免停容器后长时间阻塞")
                .isEqualTo(COMMAND_TIMEOUT);

        String token = createSession("alice_01");
        String sessionKey = "auth:session:" + jwtDecoder.decode(token).getId();
        assertThat(redisTemplate.hasKey(sessionKey)).isTrue();

        REDIS.stop();
        assertThat(REDIS.isRunning()).isFalse();

        HttpResponse<String> profile = assertTimeoutPreemptively(REQUEST_TIMEOUT,
                () -> getProfile(token));
        assertServiceUnavailable(profile);
        assertThat(profile.body()).doesNotContain(token).doesNotContain("alice_01")
                .doesNotContain("accountId").doesNotContain("username");

        HttpResponse<String> login = assertTimeoutPreemptively(REQUEST_TIMEOUT,
                () -> post("/api/v1/sessions",
                "{\"username\":\"alice_01\",\"password\":\"" + PASSWORD + "\"}"));
        assertServiceUnavailable(login);
        assertThat(login.body()).doesNotContain("accessToken").doesNotContain("Bearer");
    }

    private String createSession(String username) throws Exception {
        HttpResponse<String> registration = post("/api/v1/users",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD
                + "\",\"confirmPassword\":\"" + PASSWORD + "\"}");
        assertThat(registration.statusCode()).isEqualTo(201);

        HttpResponse<String> login = post("/api/v1/sessions",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.statusCode()).isEqualTo(201);
        String token = (String) jsonBody(login).get("accessToken");
        assertThat(token).isNotBlank();
        return token;
    }

    private HttpResponse<String> getProfile(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(baseUrl + "/api/v1/users/me"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertServiceUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("code")).isEqualTo("C0001");
        assertThat((String) body.get("message")).isNotBlank();
        String traceId = (String) body.get("traceId");
        assertThat(traceId).isNotBlank();
        assertThat(response.headers().firstValue("X-Request-Id").orElse(null))
                .isEqualTo(traceId);
        assertThat(response.headers().allValues("WWW-Authenticate")).isEmpty();
        assertThat(body).doesNotContainKeys("accessToken", "user", "username", "accountId",
                "role", "tokenVersion", "stackTrace", "exception");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return jsonMapper.readValue(response.body(), Map.class);
    }
}
