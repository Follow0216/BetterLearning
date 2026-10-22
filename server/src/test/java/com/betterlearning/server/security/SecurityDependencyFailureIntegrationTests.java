package com.betterlearning.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.manager.SessionManager;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestHttpClient;

import org.junit.jupiter.api.BeforeEach;
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

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("security-it")
@Tag("integration")
class SecurityDependencyFailureIntegrationTests {

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

    private TestHttpClient http;

    @BeforeEach
    void createClient() {
        http = new TestHttpClient(port, jsonMapper);
    }

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
        HttpResponse<String> response = http.get("/api/v1/test/protected", token);
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
        return http.jsonBody(response);
    }
}
