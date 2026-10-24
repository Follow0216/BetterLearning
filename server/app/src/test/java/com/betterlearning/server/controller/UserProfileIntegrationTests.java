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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class UserProfileIntegrationTests {

    private static final String PASSWORD = " Passw0rd! ";

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-profile-integration-secret".getBytes(StandardCharsets.UTF_8));

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

    @BeforeEach
    void cleanState() {
        http = new TestHttpClient(port, jsonMapper);
        TestData.clean(jdbcTemplate, redisTemplate);
    }

    @Test
    void requiresAuthentication() throws Exception {
        HttpResponse<String> get = http.get("/api/v1/users/me", null);
        assertThat(get.statusCode()).isEqualTo(401);
        assertThat(http.jsonBody(get).get("code")).isEqualTo("A0200");

        HttpResponse<String> patch = patch(null, "{}");
        assertThat(patch.statusCode()).isEqualTo(401);
        assertThat(http.jsonBody(patch).get("code")).isEqualTo("A0200");
    }

    @Test
    void returnsCurrentProfileWithStringAccountId() throws Exception {
        long accountId = register("alice_01");
        String token = login("alice_01");

        HttpResponse<String> response = getProfile(token);

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("accountId")).isInstanceOf(String.class);
        assertThat(body.get("accountId")).isEqualTo(Long.toString(accountId));
        assertThat(body.get("username")).isEqualTo("alice_01");
        assertThat(body.get("role")).isEqualTo("USER");
        assertThat(body.get("phone")).isNull();
        assertThat(body.get("email")).isNull();
        assertThat(body.get("createdAt")).isNotNull();
        assertThat(body.get("updatedAt")).isNotNull();
        assertThat(body.keySet()).containsExactlyInAnyOrder(
                "accountId", "username", "phone", "email", "role", "createdAt", "updatedAt");
        assertThat(response.body()).doesNotContain("password").doesNotContain("tokenVersion");
    }

    @Test
    void addsReplacesAndPreservesFields() throws Exception {
        register("alice_01");
        String token = login("alice_01");

        Map<String, Object> phoneAdded = jsonBody(patch(token, "{\"phone\":\"13800138000\"}"));
        assertThat(phoneAdded.get("phone")).isEqualTo("+8613800138000");
        assertThat(phoneAdded.get("email")).isNull();

        Map<String, Object> emailAdded = jsonBody(patch(token, "{\"email\":\"a@example.com\"}"));
        assertThat(emailAdded.get("phone")).isEqualTo("+8613800138000");
        assertThat(emailAdded.get("email")).isEqualTo("a@example.com");

        Map<String, Object> phoneReplaced = jsonBody(
                patch(token, "{\"phone\":\"+8613900139000\"}"));
        assertThat(phoneReplaced.get("phone")).isEqualTo("+8613900139000");
        assertThat(phoneReplaced.get("email")).isEqualTo("a@example.com");
    }

    @Test
    void unbindsExplicitNullAndRejectsBlankValues() throws Exception {
        register("alice_01");
        String token = login("alice_01");
        patch(token, "{\"phone\":\"13800138000\",\"email\":\"a@example.com\"}");

        Map<String, Object> phoneUnbound = jsonBody(patch(token, "{\"phone\":null}"));
        assertThat(phoneUnbound.get("phone")).isNull();
        assertThat(phoneUnbound.get("email")).isEqualTo("a@example.com");

        Map<String, Object> emailUnbound = jsonBody(patch(token, "{\"email\":null}"));
        assertThat(emailUnbound.get("phone")).isNull();
        assertThat(emailUnbound.get("email")).isNull();

        HttpResponse<String> blankPhone = patch(token, "{\"phone\":\"\"}");
        assertThat(blankPhone.statusCode()).isEqualTo(400);
        assertThat(jsonBody(blankPhone).get("code")).isEqualTo("A0400");
        assertThat((Map<String, Object>) jsonBody(blankPhone).get("fieldErrors")).containsKey("phone");

        HttpResponse<String> blankEmail = patch(token, "{\"email\":\"   \"}");
        assertThat(blankEmail.statusCode()).isEqualTo(400);
        assertThat(jsonBody(blankEmail).get("code")).isEqualTo("A0400");
        assertThat((Map<String, Object>) jsonBody(blankEmail).get("fieldErrors")).containsKey("email");

        Map<String, Object> current = jsonBody(getProfile(token));
        assertThat(current.get("phone")).isNull();
        assertThat(current.get("email")).isNull();
    }

    @Test
    void rejectsInvalidFormats() throws Exception {
        register("alice_01");
        String token = login("alice_01");

        HttpResponse<String> phone = patch(token, "{\"phone\":\"12345\"}");
        assertThat(phone.statusCode()).isEqualTo(400);
        assertThat(jsonBody(phone).get("code")).isEqualTo("A0400");

        HttpResponse<String> email = patch(token, "{\"email\":\"not-an-email\"}");
        assertThat(email.statusCode()).isEqualTo(400);
        assertThat(jsonBody(email).get("code")).isEqualTo("A0400");

        Map<String, Object> current = jsonBody(getProfile(token));
        assertThat(current.get("phone")).isNull();
        assertThat(current.get("email")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"username\":\"other_01\"}",
            "{\"role\":\"ADMIN\"}",
            "{\"accountId\":\"999\"}",
            "{\"phoneProvided\":true}"
    })
    void rejectsClientControlledFields(String body) throws Exception {
        long accountId = register("alice_01");
        String token = login("alice_01");
        patch(token, "{\"phone\":\"13800138000\",\"email\":\"a@example.com\"}");

        HttpResponse<String> response = patch(token, body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jsonBody(response).get("code")).isEqualTo("A0427");
        Map<String, Object> current = jsonBody(getProfile(token));
        assertThat(current.get("accountId")).isEqualTo(Long.toString(accountId));
        assertThat(current.get("username")).isEqualTo("alice_01");
        assertThat(current.get("role")).isEqualTo("USER");
        assertThat(current.get("phone")).isEqualTo("+8613800138000");
        assertThat(current.get("email")).isEqualTo("a@example.com");
    }

    @Test
    void returnsCurrentProfileForEmptyPatchWithoutTouchingUpdatedAt() throws Exception {
        register("alice_01");
        String token = login("alice_01");
        patch(token, "{\"phone\":\"13800138000\"}");
        Map<String, Object> before = jsonBody(getProfile(token));

        HttpResponse<String> response = patch(token, "{}");

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> after = jsonBody(response);
        assertThat(after.get("updatedAt")).isEqualTo(before.get("updatedAt"));
        assertThat(after.get("phone")).isEqualTo(before.get("phone"));
        assertThat(jsonBody(getProfile(token)).get("updatedAt"))
                .isEqualTo(before.get("updatedAt"));
    }

    @Test
    void returnsConflictForOtherUsersContactAndKeepsOriginalProfile() throws Exception {
        register("alice_01");
        register("bob_01");
        String aliceToken = login("alice_01");
        String bobToken = login("bob_01");
        patch(aliceToken, "{\"phone\":\"13800138000\",\"email\":\"a@example.com\"}");
        patch(bobToken, "{\"phone\":\"13900139000\",\"email\":\"b@example.com\"}");

        HttpResponse<String> phoneConflict =
                patch(bobToken, "{\"phone\":\"+8613800138000\"}");
        assertThat(phoneConflict.statusCode()).isEqualTo(409);
        assertThat(jsonBody(phoneConflict).get("code")).isEqualTo("A0154");
        assertThat(jsonBody(phoneConflict).get("message")).isEqualTo("该手机号已绑定");

        HttpResponse<String> emailConflict =
                patch(bobToken, "{\"email\":\"a@example.com\"}");
        assertThat(emailConflict.statusCode()).isEqualTo(409);
        assertThat(jsonBody(emailConflict).get("code")).isEqualTo("A0155");
        assertThat(jsonBody(emailConflict).get("message")).isEqualTo("该邮箱已绑定");

        Map<String, Object> bob = jsonBody(getProfile(bobToken));
        assertThat(bob.get("phone")).isEqualTo("+8613900139000");
        assertThat(bob.get("email")).isEqualTo("b@example.com");
    }

    @Test
    void keepsPhoneUnchangedWhenEmailConflictsInSameRequest() throws Exception {
        register("alice_01");
        register("bob_01");
        String aliceToken = login("alice_01");
        String bobToken = login("bob_01");
        patch(aliceToken, "{\"email\":\"a@example.com\"}");
        patch(bobToken, "{\"phone\":\"13900139000\",\"email\":\"b@example.com\"}");

        HttpResponse<String> response = patch(bobToken,
                "{\"phone\":\"13700137000\",\"email\":\"a@example.com\"}");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(jsonBody(response).get("code")).isEqualTo("A0155");
        Map<String, Object> bob = jsonBody(getProfile(bobToken));
        assertThat(bob.get("phone")).isEqualTo("+8613900139000");
        assertThat(bob.get("email")).isEqualTo("b@example.com");
    }

    @Test
    void allowsRebindingOwnContactValues() throws Exception {
        register("alice_01");
        String token = login("alice_01");
        patch(token, "{\"phone\":\"13800138000\",\"email\":\"a@example.com\"}");

        HttpResponse<String> response = patch(token,
                "{\"phone\":\"13800138000\",\"email\":\"a@example.com\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("phone")).isEqualTo("+8613800138000");
        assertThat(body.get("email")).isEqualTo("a@example.com");
    }

    @Test
    void normalizesDomesticPhoneAndEmailCase() throws Exception {
        register("alice_01");
        String token = login("alice_01");

        Map<String, Object> body = jsonBody(patch(token,
                "{\"phone\":\" 13800138000 \",\"email\":\" Alice@Example.COM \"}"));

        assertThat(body.get("phone")).isEqualTo("+8613800138000");
        assertThat(body.get("email")).isEqualTo("alice@example.com");
    }

    @Test
    void allowsOnlyOneConcurrentBindingOfSameContact() throws Exception {
        register("alice_01");
        register("bob_01");
        String aliceToken = login("alice_01");
        String bobToken = login("bob_01");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(
                    () -> concurrentPatch(aliceToken, ready, start));
            Future<Integer> second = executor.submit(
                    () -> concurrentPatch(bobToken, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            Integer bound = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM app_user WHERE phone = ?",
                    Integer.class, "+8613900139000");
            Integer unbound = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM app_user WHERE phone IS NULL", Integer.class);
            assertThat(bound).isEqualTo(1);
            assertThat(unbound).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private int concurrentPatch(String token, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发测试未能同时开始");
        }
        return patch(token, "{\"phone\":\"13900139000\"}").statusCode();
    }

    private long register(String username) throws Exception {
        HttpResponse<String> response = http.post("/api/v1/users",
                TestData.registerJson(username, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return Long.parseLong((String) http.jsonBody(response).get("accountId"));
    }

    private String login(String username) throws Exception {
        HttpResponse<String> response = http.post("/api/v1/sessions",
                TestData.loginJson(username, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(response).get("accessToken");
    }

    private HttpResponse<String> getProfile(String token) throws Exception {
        return http.get("/api/v1/users/me", token);
    }

    private HttpResponse<String> patch(String token, String body) throws Exception {
        return http.patch("/api/v1/users/me", body, token);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return http.jsonBody(response);
    }
}
