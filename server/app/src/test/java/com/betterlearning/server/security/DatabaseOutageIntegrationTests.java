package com.betterlearning.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import com.betterlearning.server.support.IntegrationContainers;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("security-it")
@Tag("integration")
class DatabaseOutageIntegrationTests {

    private static final String PASSWORD = " Passw0rd! ";

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-database-outage-secret".getBytes(StandardCharsets.UTF_8));

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(30);

    private static final int POSTGRES_HOST_PORT = findFreePort();

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres()
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(POSTGRES_HOST_PORT),
            ExposedPort.tcp(5432))));

    @Container
    static final GenericContainer<?> REDIS =
            IntegrationContainers.redis();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "500");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
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
    private JsonMapper jsonMapper;

    @Test
    void returnsServiceUnavailableForAllDatabasePathsAndRecovers() throws Exception {
        String token = registerAndLogin("alice_01");
        assertThat(getProfile(token).statusCode()).isEqualTo(200);

        HttpResponse<String> profileDuringOutage;
        HttpResponse<String> loginDuringOutage;
        HttpResponse<String> registrationDuringOutage;
        try {
            stopPostgres();
            profileDuringOutage = assertTimeoutPreemptively(REQUEST_TIMEOUT,
                    () -> getProfile(token));
            loginDuringOutage = assertTimeoutPreemptively(REQUEST_TIMEOUT,
                    () -> login("alice_01"));
            registrationDuringOutage = assertTimeoutPreemptively(REQUEST_TIMEOUT,
                    () -> register("bob_01"));
        } finally {
            startPostgresAndAwaitReady();
        }

        assertServiceUnavailable(profileDuringOutage, token);
        assertServiceUnavailable(loginDuringOutage, null);
        assertServiceUnavailable(registrationDuringOutage, null);

        awaitProfile(token);
        assertThat(login("alice_01").statusCode()).isEqualTo(201);
        assertThat(register("recovery_01").statusCode()).isEqualTo(201);
    }

    private String registerAndLogin(String username) throws Exception {
        assertThat(register(username).statusCode()).isEqualTo(201);
        HttpResponse<String> login = login(username);
        assertThat(login.statusCode()).isEqualTo(201);
        String token = (String) jsonBody(login).get("accessToken");
        assertThat(token).isNotBlank();
        return token;
    }

    private HttpResponse<String> register(String username) throws Exception {
        return post("/api/v1/users", "{\"username\":\"" + username
                + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\"" + PASSWORD + "\"}");
    }

    private HttpResponse<String> login(String username) throws Exception {
        return post("/api/v1/sessions", "{\"username\":\"" + username
                + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    private HttpResponse<String> getProfile(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void awaitProfile(String token) throws Exception {
        long deadline = System.nanoTime() + RECOVERY_TIMEOUT.toNanos();
        HttpResponse<String> lastResponse = null;
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                lastResponse = getProfile(token);
                if (lastResponse.statusCode() == 200) {
                    return;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(200);
        }
        if (lastResponse != null) {
            assertThat(lastResponse.statusCode()).isEqualTo(200);
            return;
        }
        throw lastFailure == null ? new IllegalStateException("资料接口未恢复") : lastFailure;
    }

    private static void stopPostgres() {
        DockerClient client = DockerClientFactory.instance().client();
        client.stopContainerCmd(POSTGRES.getContainerId()).exec();
    }

    private static void startPostgresAndAwaitReady() {
        DockerClient client = DockerClientFactory.instance().client();
        client.startContainerCmd(POSTGRES.getContainerId()).exec();
        long deadline = System.nanoTime() + RECOVERY_TIMEOUT.toNanos();
        SQLException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(), POSTGRES.getPassword())) {
                return;
            } catch (SQLException exception) {
                lastFailure = exception;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待 PostgreSQL 恢复时被中断", interrupted);
                }
            }
        }
        throw new IllegalStateException("PostgreSQL 未在超时内恢复", lastFailure);
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("无法分配 PostgreSQL 测试端口", exception);
        }
    }

    private void assertServiceUnavailable(HttpResponse<String> response, String token) {
        assertThat(response.statusCode()).isEqualTo(503);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("code")).isEqualTo("C0001");
        assertThat((String) body.get("message")).isNotBlank();
        String traceId = (String) body.get("traceId");
        assertThat(traceId).isNotBlank();
        assertThat(response.headers().firstValue("X-Request-Id").orElse(null)).isEqualTo(traceId);
        assertThat(response.headers().allValues("WWW-Authenticate")).isEmpty();
        if (token != null) {
            assertThat(response.body()).doesNotContain(token);
        }
        assertThat(response.body()).doesNotContain("password", "hash", "SELECT", "INSERT",
                "UPDATE", "app_user", "alice_01", "bob_01", "13800138000", "alice@example.com",
                "accessToken", "Bearer");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return jsonMapper.readValue(response.body(), Map.class);
    }
}
