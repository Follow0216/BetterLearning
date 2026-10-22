package com.betterlearning.server.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class ChangePasswordIntegrationTests {

    private static final String OLD_PASSWORD = " Passw0rd! ";

    private static final String NEW_PASSWORD = "NewPassw0rd!";

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-password-integration-secret".getBytes(StandardCharsets.UTF_8));

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

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanState() {
        http = new TestHttpClient(port, jsonMapper);
        TestData.clean(jdbcTemplate, redisTemplate);
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
        HttpResponse<String> response = http.post("/api/v1/users",
                TestData.registerJson(username, OLD_PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return Long.parseLong((String) http.jsonBody(response).get("accountId"));
    }

    private String loginToken(String username, String password) throws Exception {
        HttpResponse<String> response = login(username, password);
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(response).get("accessToken");
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        return http.post("/api/v1/sessions", TestData.loginJson(username, password));
    }

    private HttpResponse<String> changePassword(String token, String body) throws Exception {
        return http.patch("/api/v1/users/me/password", body, token);
    }

    private HttpResponse<String> me(String token) throws Exception {
        return http.get("/api/v1/users/me", token);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return http.jsonBody(response);
    }

    private record ChangeResult(int status, String code, String message) {
    }
}
