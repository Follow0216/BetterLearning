package com.betterlearning.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.model.dataobject.UserDO;
import com.betterlearning.server.model.dto.ChangePasswordDTO;
import com.betterlearning.server.service.UserService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class ConcurrentCredentialIntegrationTests {

    private static final String PASSWORD = " Passw0rd! ";

    private static final String NEW_PASSWORD_A = "NewPassA1!";

    private static final String NEW_PASSWORD_B = "NewPassB2@";

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
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoSpyBean
    private UserDao userDao;

    @BeforeEach
    void cleanState() {
        http = new TestHttpClient(port, jsonMapper);
        TestData.clean(jdbcTemplate, redisTemplate);
    }

    @Test
    void registersExactlyOneAccountForConcurrentlyNormalizedUsername() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> first = executor.submit(
                    concurrentRegistration("  Alice_01  ", start));
            Future<HttpResponse<String>> second = executor.submit(
                    concurrentRegistration("ALICE_01", start));

            List<HttpResponse<String>> responses = Arrays.asList(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));

            assertThat(responses).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(201, 409);

            HttpResponse<String> created = responses.stream()
                    .filter(response -> response.statusCode() == 201)
                    .findFirst()
                    .orElseThrow();
            assertThat(jsonBody(created).get("username")).isEqualTo("alice_01");

            HttpResponse<String> conflict = responses.stream()
                    .filter(response -> response.statusCode() == 409)
                    .findFirst()
                    .orElseThrow();
            assertThat(jsonBody(conflict).get("code")).isEqualTo("A0111");

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM app_user WHERE username = ?", Integer.class,
                    "alice_01")).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            start.reset();
        }
    }

    @Test
    void appliesRealCasForConcurrentPasswordChangesFromSameSnapshot() throws Exception {
        long accountId = insertUser("alice_01");
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Integer> affectedRows = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            barrier.await(5, TimeUnit.SECONDS);
            Object updated = invocation.callRealMethod();
            affectedRows.add((Integer) updated);
            return updated;
        }).when(userDao).updatePassword(anyLong(), anyString(), anyInt(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> firstChange = executor.submit(
                    concurrentPasswordChange(accountId, 0, NEW_PASSWORD_A));
            Future<Object> secondChange = executor.submit(
                    concurrentPasswordChange(accountId, 0, NEW_PASSWORD_B));

            List<Object> results = Arrays.asList(
                    firstChange.get(10, TimeUnit.SECONDS),
                    secondChange.get(10, TimeUnit.SECONDS));

            assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
            BusinessException failure = results.stream()
                    .filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PASSWORD_CHANGE_CONFLICT);

            assertThat(affectedRows).containsExactlyInAnyOrder(1, 0);

            UserDO stored = userDao.findByAccountId(accountId).orElseThrow();
            assertThat(stored.getTokenVersion()).isEqualTo(1);
            assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isFalse();

            boolean firstWon = results.get(0) == null;
            assertThat(passwordEncoder.matches(NEW_PASSWORD_A, stored.getPasswordHash()))
                    .isEqualTo(firstWon);
            assertThat(passwordEncoder.matches(NEW_PASSWORD_B, stored.getPasswordHash()))
                    .isEqualTo(!firstWon);
        } finally {
            executor.shutdownNow();
            barrier.reset();
        }
    }

    private Callable<HttpResponse<String>> concurrentRegistration(String username,
            CyclicBarrier start) {
        return () -> {
            start.await(5, TimeUnit.SECONDS);
            return post("/api/v1/users",
                    "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD
                            + "\",\"confirmPassword\":\"" + PASSWORD + "\"}");
        };
    }

    private Callable<Object> concurrentPasswordChange(long accountId, int authenticatedTokenVersion,
            String newPassword) {
        return () -> {
            try {
                userService.changePassword(accountId, authenticatedTokenVersion,
                        changeRequest(newPassword));
                return null;
            } catch (BusinessException exception) {
                return exception;
            }
        };
    }

    private long insertUser(String username) {
        jdbcTemplate.update("INSERT INTO app_user (username, password_hash) VALUES (?, ?)",
                username, passwordEncoder.encode(PASSWORD));
        Long accountId = jdbcTemplate.queryForObject(
                "SELECT account_id FROM app_user WHERE username = ?", Long.class, username);
        assertThat(accountId).isNotNull();
        return accountId;
    }

    private static ChangePasswordDTO changeRequest(String newPassword) {
        ChangePasswordDTO request = new ChangePasswordDTO();
        request.setOldPassword(PASSWORD);
        request.setNewPassword(newPassword);
        request.setConfirmPassword(newPassword);
        return request;
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.post(path, body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return http.jsonBody(response);
    }
}
