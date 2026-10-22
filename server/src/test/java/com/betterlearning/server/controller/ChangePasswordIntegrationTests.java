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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class ChangePasswordIntegrationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.3")
            .asCompatibleSubstituteFor("postgres");

    private static final String OLD_PASSWORD = " Passw0rd! ";

    private static final String NEW_PASSWORD = "NewPassw0rd!";

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-password-integration-secret".getBytes(StandardCharsets.UTF_8));

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
    private JsonMapper jsonMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

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
    void changesPasswordAndRejectsOldCredentials() throws Exception {
        long accountId = register("alice_01");
        String oldHash = storedHash(accountId);
        assertThat(storedTokenVersion(accountId)).isZero();
        String token = loginToken("alice_01", OLD_PASSWORD);

        HttpResponse<String> response = changePassword(token,
                changeBody(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        String newHash = storedHash(accountId);
        assertThat(newHash).isNotEqualTo(oldHash);
        assertThat(storedTokenVersion(accountId)).isEqualTo(1);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, newHash)).isTrue();

        HttpResponse<String> oldLogin = login("alice_01", OLD_PASSWORD);
        assertThat(oldLogin.statusCode()).isEqualTo(401);
        assertThat(jsonBody(oldLogin).get("code")).isEqualTo("A0200");
        assertThat(jsonBody(oldLogin).get("message")).isEqualTo("用户名或密码错误");

        HttpResponse<String> newLogin = login("alice_01", NEW_PASSWORD);
        assertThat(newLogin.statusCode()).isEqualTo(201);
        Map<String, Object> session = jsonBody(newLogin);
        assertThat(session.get("accessToken")).isInstanceOf(String.class);
        assertThat(session.get("tokenType")).isEqualTo("Bearer");
        assertThat((Map<String, Object>) session.get("user")).containsEntry("username", "alice_01");
    }

    @Test
    void invalidatesEverySessionIssuedBeforeChange() throws Exception {
        register("alice_01");
        String first = loginToken("alice_01", OLD_PASSWORD);
        String second = loginToken("alice_01", OLD_PASSWORD);
        assertThat(me(first).statusCode()).isEqualTo(200);
        assertThat(me(second).statusCode()).isEqualTo(200);

        HttpResponse<String> response = changePassword(first,
                changeBody(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(me(first).statusCode()).isEqualTo(401);
        assertThat(me(second).statusCode()).isEqualTo(401);
        assertThat(login("alice_01", NEW_PASSWORD).statusCode()).isEqualTo(201);
    }

    @Test
    void rejectsWrongOldPasswordAndKeepsSessionUsable() throws Exception {
        long accountId = register("alice_01");
        String oldHash = storedHash(accountId);
        String token = loginToken("alice_01", OLD_PASSWORD);

        HttpResponse<String> response = changePassword(token,
                changeBody("WrongPassw0rd!", NEW_PASSWORD, NEW_PASSWORD));

        assertThat(response.statusCode()).isEqualTo(400);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("code")).isEqualTo("A0400");
        assertThat(body.get("message")).isEqualTo("请求参数不合法");
        assertThat((Map<String, Object>) body.get("fieldErrors"))
                .containsEntry("oldPassword", "旧密码错误");
        assertThat(response.body()).doesNotContain("WrongPassw0rd!").doesNotContain(NEW_PASSWORD);
        assertThat(storedHash(accountId)).isEqualTo(oldHash);
        assertThat(storedTokenVersion(accountId)).isZero();
        assertThat(me(token).statusCode()).isEqualTo(200);
        assertThat(login("alice_01", OLD_PASSWORD).statusCode()).isEqualTo(201);
    }

    @Test
    void rejectsMismatchedConfirmationAndKeepsSessionUsable() throws Exception {
        long accountId = register("alice_01");
        String oldHash = storedHash(accountId);
        String token = loginToken("alice_01", OLD_PASSWORD);

        HttpResponse<String> response = changePassword(token,
                changeBody(OLD_PASSWORD, NEW_PASSWORD, "AnotherPassw0rd!"));

        assertThat(response.statusCode()).isEqualTo(400);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("code")).isEqualTo("A0400");
        assertThat((Map<String, Object>) body.get("fieldErrors"))
                .containsEntry("confirmPassword", "两次输入的密码不一致");
        assertThat(response.body()).doesNotContain(NEW_PASSWORD).doesNotContain("AnotherPassw0rd!");
        assertThat(storedHash(accountId)).isEqualTo(oldHash);
        assertThat(storedTokenVersion(accountId)).isZero();
        assertThat(me(token).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsUnknownFieldsWithoutChangingPassword() throws Exception {
        long accountId = register("alice_01");
        String oldHash = storedHash(accountId);
        String token = loginToken("alice_01", OLD_PASSWORD);

        HttpResponse<String> response = changePassword(token,
                "{\"oldPassword\":\"" + OLD_PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD
                + "\",\"confirmPassword\":\"" + NEW_PASSWORD + "\",\"role\":\"ADMIN\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jsonBody(response).get("code")).isEqualTo("A0427");
        assertThat(storedHash(accountId)).isEqualTo(oldHash);
        assertThat(storedTokenVersion(accountId)).isZero();
    }

    @Test
    void rejectsNewPasswordThatViolatesExistingRules() throws Exception {
        long accountId = register("alice_01");
        String oldHash = storedHash(accountId);
        String token = loginToken("alice_01", OLD_PASSWORD);

        HttpResponse<String> response = changePassword(token,
                changeBody(OLD_PASSWORD, "short", "short"));

        assertThat(response.statusCode()).isEqualTo(400);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("code")).isEqualTo("A0400");
        assertThat((Map<String, Object>) body.get("fieldErrors")).containsKey("newPassword");
        assertThat(storedHash(accountId)).isEqualTo(oldHash);
        assertThat(storedTokenVersion(accountId)).isZero();
    }

    @Test
    void requiresAuthentication() throws Exception {
        HttpResponse<String> response = changePassword(null,
                changeBody(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(jsonBody(response).get("code")).isEqualTo("A0200");
    }

    @Test
    void allowsOnlyOneConcurrentChange() throws Exception {
        long accountId = register("alice_01");
        String first = loginToken("alice_01", OLD_PASSWORD);
        String second = loginToken("alice_01", OLD_PASSWORD);
        int versionBefore = storedTokenVersion(accountId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ChangeResult> firstChange = executor.submit(
                    () -> concurrentChange(first, ready, start));
            Future<ChangeResult> secondChange = executor.submit(
                    () -> concurrentChange(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ChangeResult> results = List.of(
                    firstChange.get(10, TimeUnit.SECONDS),
                    secondChange.get(10, TimeUnit.SECONDS));

            assertThat(results.stream().filter(result -> result.status() == 204).count())
                    .isEqualTo(1);
            ChangeResult failure = results.stream()
                    .filter(result -> result.status() != 204)
                    .findFirst()
                    .orElseThrow();
            if (failure.status() == 409) {
                assertThat(failure.code()).isEqualTo("A0444");
                assertThat(failure.message()).isEqualTo("密码已变更，请重新登录");
            } else {
                assertThat(failure.status()).isEqualTo(401);
                assertThat(failure.code()).isEqualTo("A0200");
            }

            assertThat(storedTokenVersion(accountId)).isEqualTo(versionBefore + 1);
            assertThat(passwordEncoder.matches(NEW_PASSWORD, storedHash(accountId))).isTrue();
            assertThat(login("alice_01", OLD_PASSWORD).statusCode()).isEqualTo(401);
            assertThat(login("alice_01", NEW_PASSWORD).statusCode()).isEqualTo(201);
        } finally {
            executor.shutdownNow();
        }
    }

    private ChangeResult concurrentChange(String token, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发测试未能同时开始");
        }
        HttpResponse<String> response = changePassword(token,
                changeBody(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));
        if (response.statusCode() == 204) {
            return new ChangeResult(204, null, null);
        }
        Map<String, Object> body = jsonBody(response);
        return new ChangeResult(response.statusCode(), (String) body.get("code"),
                (String) body.get("message"));
    }

    private long register(String username) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/users"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"" + username + "\",\"password\":\"" + OLD_PASSWORD
                + "\",\"confirmPassword\":\"" + OLD_PASSWORD + "\"}",
                StandardCharsets.UTF_8)));
        assertThat(response.statusCode()).isEqualTo(201);
        return Long.parseLong((String) jsonBody(response).get("accountId"));
    }

    private String loginToken(String username, String password) throws Exception {
        HttpResponse<String> response = login(username, password);
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) jsonBody(response).get("accessToken");
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/sessions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                        StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> changePassword(String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/api/v1/users/me/password"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return send(builder);
    }

    private HttpResponse<String> me(String token) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/users/me"))
                .header("Authorization", "Bearer " + token)
                .GET());
    }

    private String changeBody(String oldPassword, String newPassword, String confirmPassword) {
        return "{\"oldPassword\":\"" + oldPassword + "\",\"newPassword\":\"" + newPassword
                + "\",\"confirmPassword\":\"" + confirmPassword + "\"}";
    }

    private String storedHash(long accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_user WHERE account_id = ?", String.class, accountId);
    }

    private int storedTokenVersion(long accountId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT token_version FROM app_user WHERE account_id = ?", Integer.class, accountId);
        return version == null ? -1 : version;
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return jsonMapper.readValue(response.body(), Map.class);
    }

    private record ChangeResult(int status, String code, String message) {
    }
}
