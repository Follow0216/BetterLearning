package com.betterlearning.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
class JwtRejectionIntegrationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.3")
            .asCompatibleSubstituteFor("postgres");

    private static final String PASSWORD = " Passw0rd! ";

    private static final String SECRET = "betterlearning-security-integration-secret";

    private static final String WRONG_SECRET = "betterlearning-wrong-signing-secret-value";

    private static final String ISSUER = "betterlearning-test";

    private static final String AUDIENCE = "betterlearning-client";

    private static final String HS256 = "HS256";

    private static final String HS384 = "HS384";

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
        registry.add("app.jwt.secret-base64", () -> base64Secret(SECRET));
        registry.add("app.jwt.issuer", () -> ISSUER);
        registry.add("app.jwt.audience", () -> AUDIENCE);
        registry.add("app.auth.login.ip-limit", () -> "1000");
        registry.add("app.auth.login.username-limit", () -> "1000");
        registry.add("app.auth.registration.ip-limit", () -> "1000");
        registry.add("app.cors.allowed-origins[0]", () -> "http://localhost:3000");
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    private String baseUrl;

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
        baseUrl = "http://127.0.0.1:" + port;
        jdbcTemplate.update("TRUNCATE TABLE app_user RESTART IDENTITY");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void acceptsValidTokenAsControl() throws Exception {
        String token = createSession("alice_01");

        HttpResponse<String> response = getProfile(token);

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("username")).isEqualTo("alice_01");
        assertThat(body.get("role")).isEqualTo("USER");
        assertThat(response.body()).doesNotContain(token).doesNotContain("password");
    }

    @Test
    void rejectsExpiredTokenEvenWhenSessionStillExists() throws Exception {
        String token = createSession("alice_01");
        Jwt jwt = jwtDecoder.decode(token);
        String sessionKey = "auth:session:" + jwt.getId();
        assertThat(redisTemplate.hasKey(sessionKey)).isTrue();

        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        String expiredToken = sign(HS256, SECRET, claims(jwt.getSubject(), ISSUER, AUDIENCE,
                issuedAt, issuedAt.plusSeconds(3600), jwt.getId(),
                tokenVersion(jwt)));

        HttpResponse<String> response = getProfile(expiredToken);

        assertRejected(response, expiredToken);
        assertThat(redisTemplate.hasKey(sessionKey))
                .as("过期 JWT 被拒绝后应保留 Redis 会话，证明拒绝来自 JWT 校验")
                .isTrue();
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() throws Exception {
        String token = createSession("alice_01");
        Jwt jwt = jwtDecoder.decode(token);

        String forgedToken = sign(HS256, WRONG_SECRET, validClaims(jwt));

        HttpResponse<String> response = getProfile(forgedToken);

        assertRejected(response, forgedToken);
        assertThat(redisTemplate.hasKey("auth:session:" + jwt.getId())).isTrue();
    }

    @Test
    void rejectsTokenWithWrongIssuer() throws Exception {
        String token = createSession("alice_01");
        Jwt jwt = jwtDecoder.decode(token);
        String wrongIssuerToken = sign(HS256, SECRET, claims(jwt.getSubject(), "evil-issuer",
                AUDIENCE, issuedAt(), expiresAt(), jwt.getId(), tokenVersion(jwt)));

        HttpResponse<String> response = getProfile(wrongIssuerToken);

        assertRejected(response, wrongIssuerToken);
    }

    @Test
    void rejectsTokenWithWrongAudience() throws Exception {
        String token = createSession("alice_01");
        Jwt jwt = jwtDecoder.decode(token);
        String wrongAudienceToken = sign(HS256, SECRET, claims(jwt.getSubject(), ISSUER,
                "evil-client", issuedAt(), expiresAt(), jwt.getId(), tokenVersion(jwt)));

        HttpResponse<String> response = getProfile(wrongAudienceToken);

        assertRejected(response, wrongAudienceToken);
    }

    @Test
    void rejectsTokenWithUnsupportedHs384Algorithm() throws Exception {
        String token = createSession("alice_01");
        Jwt jwt = jwtDecoder.decode(token);
        String hs384Token = sign(HS384, SECRET, validClaims(jwt));

        HttpResponse<String> response = getProfile(hs384Token);

        assertRejected(response, hs384Token);
    }

    private String createSession(String username) throws Exception {
        HttpResponse<String> registration = post("/api/v1/users",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD
                + "\",\"confirmPassword\":\"" + PASSWORD + "\"}");
        assertThat(registration.statusCode()).isEqualTo(201);

        HttpResponse<String> login = post("/api/v1/sessions",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.statusCode()).isEqualTo(201);
        String token = (String) jsonBody(login).get("accessToken");
        assertThat(token).isNotBlank();
        return token;
    }

    private HttpResponse<String> getProfile(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(baseUrl + "/api/v1/users/me"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertRejected(HttpResponse<String> response, String token) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse(""))
                .startsWith("Bearer");
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("code")).isEqualTo("A0200");
        assertThat((String) body.get("message")).isNotBlank();
        String traceId = (String) body.get("traceId");
        assertThat(traceId).isNotBlank();
        assertThat(response.headers().firstValue("X-Request-Id").orElse(null))
                .isEqualTo(traceId);
        assertThat(response.body()).doesNotContain(token);
        assertThat(body).doesNotContainKeys("accessToken", "user", "username", "accountId",
                "role", "tokenVersion", "stackTrace", "exception");
    }

    private static Map<String, Object> validClaims(Jwt jwt) {
        return claims(jwt.getSubject(), ISSUER, AUDIENCE, issuedAt(), expiresAt(),
                jwt.getId(), tokenVersion(jwt));
    }

    private static Map<String, Object> claims(String subject, String issuer, String audience,
            Instant issuedAt, Instant expiresAt, String jwtId, int tokenVersion) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("sub", subject);
        claims.put("aud", audience);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", jwtId);
        claims.put(JwtTokenService.TOKEN_VERSION_CLAIM, tokenVersion);
        return claims;
    }

    private static Instant issuedAt() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(60);
    }

    private static Instant expiresAt() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600);
    }

    private static int tokenVersion(Jwt jwt) {
        return ((Number) jwt.getClaims().get(JwtTokenService.TOKEN_VERSION_CLAIM)).intValue();
    }

    private String sign(String algorithm, String secret, Map<String, Object> claims)
            throws Exception {
        String header = base64Url(("{\"alg\":\"" + algorithm
                + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(jsonMapper.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        String macAlgorithm = HS384.equals(algorithm) ? "HmacSHA384" : "HmacSHA256";
        Mac mac = Mac.getInstance(macAlgorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macAlgorithm));
        String signature = base64Url(
                mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        return signingInput + "." + signature;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String base64Secret(String secret) {
        return Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(HttpResponse<String> response) {
        return jsonMapper.readValue(response.body(), Map.class);
    }
}
