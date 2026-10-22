package com.betterlearning.server.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
class ApiDocumentationDisabledIntegrationTests {

    private static final String PASSWORD = " Passw0rd! ";

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-security-integration-secret".getBytes(StandardCharsets.UTF_8));

    private static final List<String> DOCUMENTATION_PATHS = List.of(
            "/v3/api-docs",
            "/v3/api-docs/swagger-config",
            "/swagger-ui/index.html",
            "/swagger-ui.html");

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
        registry.add("APP_DOCS_ENABLED", () -> "false");
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
        Map<String, Object> body = http.jsonBody(response);
        assertThat(body.get("code")).as(path).isEqualTo("A0606");
        assertThat(body.get("traceId")).as(path).isNotNull();
        assertThat(response.headers().firstValue("X-Request-Id").orElse(null))
                .as(path).isEqualTo(body.get("traceId"));
    }

    private void register(String username) throws Exception {
        HttpResponse<String> response = http.post("/api/v1/users",
                TestData.registerJson(username, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
    }

    private String login(String username) throws Exception {
        HttpResponse<String> response = http.post("/api/v1/sessions",
                TestData.loginJson(username, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(response).get("accessToken");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.get(path, token);
    }
}
