package com.betterlearning.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.exception.UserUniqueConflictException;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

/**
 * dev 账号初始化器的真实 PostgreSQL／Redis 集成测试。
 *
 * <p>启动完整应用时由 dev 初始化器创建业务账号，再通过真实 HTTP 登录并访问
 * {@code /api/v1/users/me}；其余用例直接重置数据库后重复执行初始化器，核对幂等与冲突边界。
 * 测试使用独立容器，不连接外部数据库或 Redis。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.dev-admin.enabled=false",
        DevUserAccountInitializerIntegrationTests.USERNAME_PROPERTY,
        DevUserAccountInitializerIntegrationTests.PASSWORD_PROPERTY})
@ActiveProfiles("dev")
@Tag("integration")
class DevUserAccountInitializerIntegrationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.3")
            .asCompatibleSubstituteFor("postgres");

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-dev-seed-integration-secret".getBytes(StandardCharsets.UTF_8));

    private static final String RAW_USERNAME = "  Dev_Bootstrap_User  ";

    private static final String USERNAME = "dev_bootstrap_user";

    private static final String PASSWORD = "${DEV_SEED_UNRESOLVED}";

    static final String USERNAME_PROPERTY = "SPRING_SECURITY_USER_NAME=" + RAW_USERNAME;

    static final String PASSWORD_PROPERTY = "SPRING_SECURITY_USER_PASSWORD=" + PASSWORD;

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
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private DevUserAccountInitializer initializer;

    @Autowired
    private UserDao userDao;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    private static Map<String, Object> startupAccount;

    private static boolean startupCaptured;

    private String baseUrl;

    @BeforeEach
    void resetState() {
        if (!startupCaptured) {
            startupAccount = accountRow();
            startupCaptured = true;
        }
        baseUrl = "http://127.0.0.1:" + port;
        jdbcTemplate.update("TRUNCATE TABLE question_answer, question, question_collection, app_user RESTART IDENTITY");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void startupCreatesUserAndRealLoginAndProfileWork() throws Exception {
        assertThat(startupAccount.get("username")).isEqualTo(USERNAME);
        assertThat(startupAccount.get("role")).isEqualTo("USER");
        assertThat(((Number) startupAccount.get("token_version")).intValue()).isZero();
        assertThat(passwordEncoder.matches(PASSWORD,
                (String) startupAccount.get("password_hash"))).isTrue();
        initializer.run(null);

        HttpResponse<String> login = postJson("/api/v1/sessions",
                "{\"username\":\"  DEV_BOOTSTRAP_USER  \",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.statusCode()).isEqualTo(201);
        assertThat(login.body()).doesNotContain(PASSWORD);
        String token = (String) jsonBody(login).get("accessToken");
        assertThat(token).isNotBlank();

        HttpResponse<String> profile = getWithToken("/api/v1/users/me", token);
        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(profile.body()).doesNotContain(PASSWORD);
        Map<String, Object> body = jsonBody(profile);
        assertThat(body.get("username")).isEqualTo(USERNAME);
        assertThat(body.get("role")).isEqualTo("USER");
        assertThat(body.get("accountId"))
                .isEqualTo(String.valueOf(startupAccount.get("account_id")));
    }

    @Test
    void repeatedRunsKeepExistingAccountUnchanged() {
        initializer.run(null);
        Map<String, Object> before = accountRow();

        initializer.run(null);

        assertThat(accountRow()).isEqualTo(before);
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void usernameConflictLeavesConnectionCleanForReread() {
        userDao.insert(USERNAME, passwordEncoder.encode(PASSWORD));
        String secondHash = passwordEncoder.encode(PASSWORD);

        assertThatThrownBy(() -> userDao.insert(USERNAME, secondHash))
                .isInstanceOf(UserUniqueConflictException.class);
        assertThat(userDao.findByUsername(USERNAME)).isPresent();
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void rejectsExistingUserWithDifferentPasswordWithoutChangingIt() {
        long accountId = userDao.insert(USERNAME,
                passwordEncoder.encode("OtherPass1!"));
        Map<String, Object> before = accountRow();

        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密码不匹配")
                .hasMessageNotContaining(PASSWORD)
                .hasMessageNotContaining(USERNAME);

        assertThat(accountRow()).isEqualTo(before);
        assertThat(accountRow().get("account_id")).isEqualTo(accountId);
    }

    @Test
    void rejectsExistingAdminWithoutChangingRoleOrPassword() {
        long accountId = userDao.insert(USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("UPDATE app_user SET role = 'ADMIN' WHERE account_id = ?",
                accountId);
        Map<String, Object> before = accountRow();

        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("角色不是 USER")
                .hasMessageNotContaining(USERNAME);

        assertThat(accountRow()).isEqualTo(before);
        assertThat(accountRow().get("role")).isEqualTo("ADMIN");
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> getWithToken(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) throws Exception {
        return jsonMapper.readValue(response.body(), Map.class);
    }

    private Map<String, Object> accountRow() {
        return jdbcTemplate.queryForMap(
                "SELECT account_id, username, password_hash, phone, email, role, "
                + "token_version, created_at, updated_at FROM app_user");
    }

    private int countUsers() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user",
                Integer.class);
        assertThat(count).isNotNull();
        return count;
    }
}
