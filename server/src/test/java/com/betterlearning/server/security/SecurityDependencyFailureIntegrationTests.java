package com.betterlearning.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.manager.SessionManager;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class SecurityDependencyFailureIntegrationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.3")
            .asCompatibleSubstituteFor("postgres");

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

    @MockitoBean
    private SessionManager sessionManager;

    @MockitoBean
    private UserDao userDao;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JsonMapper jsonMapper;

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void returns503WhenSessionStoreUnavailable() throws Exception {
        when(sessionManager.isActive(anyString(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        HttpResponse<String> response = getProtected();

        assertServiceUnavailable(response);
    }

    @Test
    void returns503WhenAccountLookupUnavailable() throws Exception {
        when(sessionManager.isActive(anyString(), anyLong())).thenReturn(true);
        when(userDao.findByAccountId(anyLong()))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        HttpResponse<String> response = getProtected();

        assertServiceUnavailable(response);
    }

    @Test
    void checksSessionBeforeAccountLookup() throws Exception {
        when(sessionManager.isActive(anyString(), anyLong())).thenReturn(false);
        when(userDao.findByAccountId(anyLong()))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        HttpResponse<String> response = getProtected();

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(jsonBody(response).get("code")).isEqualTo("A0200");
    }

    private HttpResponse<String> getProtected() throws Exception {
        String token = jwtTokenService.issue(1L, 0).getToken();
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/test/protected"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.body()).doesNotContain(token).doesNotContain("Bearer ");
        return response;
    }

    private void assertServiceUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("code")).isEqualTo("C0001");
        assertThat((String) body.get("message")).isNotBlank();
        assertThat(response.headers().firstValue("X-Request-Id").orElse(null))
                .isEqualTo(body.get("traceId"));
        assertThat(response.headers().allValues("WWW-Authenticate")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return jsonMapper.readValue(response.body(), Map.class);
    }
}
