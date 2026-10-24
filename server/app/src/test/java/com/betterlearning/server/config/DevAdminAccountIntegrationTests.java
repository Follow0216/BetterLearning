package com.betterlearning.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;
import com.betterlearning.server.support.TestHttpClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "SPRING_SECURITY_USER_NAME=seed_user", "SPRING_SECURITY_USER_PASSWORD=Seed-Pass123"})
@ActiveProfiles("dev")
@Tag("integration")
class DevAdminAccountIntegrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, Base64.getEncoder().encodeToString(
                "betterlearning-admin-integration-secret".getBytes(StandardCharsets.UTF_8)),
                "admin-test", "admin-client");
        IntegrationProperties.registerRateLimits(registry);
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JsonMapper json;

    @Autowired
    private DevAdminAccountInitializer adminInitializer;

    @Autowired
    private DevUserAccountInitializer userInitializer;

    @Autowired
    private UserDao dao;

    @Autowired
    private PasswordEncoder encoder;

    private TestHttpClient http;

    @BeforeEach
    void clean() {
        http = new TestHttpClient(port, json);
        TestData.clean(jdbc, redis);
        userInitializer.run(new DefaultApplicationArguments());
        adminInitializer.run(new DefaultApplicationArguments());
    }

    @Test
    void defaultAdminLogsInAndProbeEnforcesAdminRole() throws Exception {
        HttpResponse<String> login = http.post("/api/v1/sessions", TestData.loginJson("admin", "admin123"));
        assertThat(login.statusCode()).isEqualTo(201);
        Map<String, Object> session = http.jsonBody(login);
        assertThat(((Map<?, ?>) session.get("user")).get("role")).isEqualTo("ADMIN");
        HttpResponse<String> probe = http.get("/api/v1/admin/session", (String) session.get("accessToken"));
        assertThat(probe.statusCode()).isEqualTo(200);
        assertThat(http.jsonBody(probe).get("role")).isEqualTo("ADMIN");
        Map<String, Object> userSession = http.jsonBody(http.post("/api/v1/sessions",
                TestData.loginJson("seed_user", "Seed-Pass123")));
        http.assertErrorEnvelope(http.get("/api/v1/admin/session", (String) userSession.get("accessToken")),
                403, "A0300");
        http.assertErrorEnvelope(http.get("/api/v1/admin/session", null), 401, "A0200");
    }

    @Test
    void repeatedStartupPreservesHashRoleAndTokenVersion() {
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM app_user WHERE username='admin'");
        adminInitializer.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE username='admin'")).isEqualTo(before);
    }

    @Test
    void conflictingUserIsNotPromotedAndWrongPasswordNeverResetsAdmin() {
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM app_user WHERE username='admin'");
        MockEnvironment wrong = new MockEnvironment().withProperty("APP_DEV_ADMIN_PASSWORD", "Wrong-Pass123");
        assertThatThrownBy(() -> new DevAdminAccountInitializer(wrong, dao, encoder)
                .run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE username='admin'")).isEqualTo(before);
        MockEnvironment userName = new MockEnvironment().withProperty("APP_DEV_ADMIN_USERNAME", "seed_user");
        assertThatThrownBy(() -> new DevAdminAccountInitializer(userName, dao, encoder)
                .run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止自动提权");
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE username='seed_user'", String.class))
                .isEqualTo("USER");
    }

    @Test
    void overridesCreateSeparateAdminAndPublicRegistrationStillCreatesUser() throws Exception {
        MockEnvironment custom = new MockEnvironment()
                .withProperty("APP_DEV_ADMIN_USERNAME", "custom_admin")
                .withProperty("APP_DEV_ADMIN_PASSWORD", "Custom-Pass123");
        new DevAdminAccountInitializer(custom, dao, encoder).run(new DefaultApplicationArguments());
        assertThat(http.post("/api/v1/sessions", TestData.loginJson("custom_admin", "Custom-Pass123"))
                .statusCode()).isEqualTo(201);
        HttpResponse<String> registered = http.post("/api/v1/users", TestData.registerJson("normal_user", "User-Pass123"));
        assertThat(registered.statusCode()).isEqualTo(201);
        assertThat(http.jsonBody(registered).get("role")).isEqualTo("USER");
        http.assertErrorEnvelope(http.post("/api/v1/users", json.writeValueAsString(Map.of(
                "username", "evil_user", "password", "User-Pass123", "confirmPassword", "User-Pass123",
                "role", "ADMIN"))), 400, "A0427");
    }
}
