package com.betterlearning.server.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("security-it")
@Tag("integration")
class SessionLogoutIntegrationTests {

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
    private JwtDecoder jwtDecoder;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void cleanState() {
        http = new TestHttpClient(port, jsonMapper);
        TestData.clean(jdbcTemplate, redisTemplate);
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
        return http.get(path, token);
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        return http.delete(path, token);
    }

    private void assertErrorEnvelope(HttpResponse<String> response, int status, String code) {
        http.assertErrorEnvelope(response, status, code);
    }
}
