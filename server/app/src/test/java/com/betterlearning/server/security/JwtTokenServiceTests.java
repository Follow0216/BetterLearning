package com.betterlearning.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.betterlearning.server.config.AppJwtProperties;
import com.betterlearning.server.config.JwtConfiguration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtTokenServiceTests {

    private static final String ISSUER = "betterlearning-test";

    private static final String AUDIENCE = "betterlearning-api";

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    private static final byte[] SECRET_BYTES =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static final byte[] OTHER_SECRET_BYTES =
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    private static final byte[] HS512_SECRET_BYTES =
            ("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                    .getBytes(StandardCharsets.UTF_8);

    private static final String TOKEN_VERSION_CLAIM = "token_version";

    private final JwtConfiguration configuration = new JwtConfiguration();

    private AppJwtProperties properties;

    private JwtEncoder encoder;

    private JwtDecoder decoder;

    private StrictJwtClaimsValidator strictValidator;

    private JwtTokenService tokenService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        properties = properties(encodeSecret(SECRET_BYTES));
        encoder = configuration.jwtEncoder(properties);
        decoder = configuration.jwtDecoder(properties, clock);
        strictValidator = new StrictJwtClaimsValidator(ISSUER, AUDIENCE, clock);
        tokenService = new JwtTokenService(encoder, properties, clock);
    }

    @Test
    void issuesAndDecodesTokenWithOnlyWhitelistedClaims() {
        IssuedToken issued = tokenService.issue(42L, 3);

        Jwt jwt = decoder.decode(issued.getToken());

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getId()).isEqualTo(issued.getJwtId())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(((Number) jwt.getClaims().get(TOKEN_VERSION_CLAIM)).intValue()).isEqualTo(3);
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder(
                "sub", "jti", "iss", "aud", "iat", "exp", TOKEN_VERSION_CLAIM);
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    void usesExactlyTwoHourLifetimeByDefault() {
        Jwt jwt = decoder.decode(tokenService.issue(42L, 0).getToken());

        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
                .isEqualTo(Duration.ofSeconds(7200));
    }

    @Test
    void issuedTokenToStringHidesTokenAndJwtId() {
        IssuedToken issued = tokenService.issue(42L, 0);

        assertThat(issued.toString())
                .startsWith("IssuedToken{")
                .contains("[PROTECTED]")
                .doesNotContain(issued.getJwtId())
                .doesNotContain(issued.getToken());
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        String token = sign(secret(OTHER_SECRET_BYTES), MacAlgorithm.HS256, validClaims());

        assertRejected(token);
    }

    @Test
    void rejectsTokenSignedWithAnotherAlgorithm() {
        String token = sign(secret(HS512_SECRET_BYTES), MacAlgorithm.HS512, validClaims());

        assertRejected(token);
    }

    @Test
    void rejectsTokenAtExactExpiryWithoutClockSkew() {
        String token = tokenService.issue(42L, 0).getToken();

        assertThat(decoderAt(NOW.plusSeconds(7199)).decode(token).getSubject()).isEqualTo("42");
        assertThatThrownBy(() -> decoderAt(NOW.plusSeconds(7200)).decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void acceptsTokenWithoutNotBefore() {
        Jwt jwt = decoder.decode(sign(validClaims()));

        assertThat(jwt.getClaims()).doesNotContainKey("nbf");
    }

    @Test
    void acceptsNotBeforeEqualToNow() {
        Jwt jwt = decoder.decode(sign(claimsWithNotBefore(NOW)));

        assertThat(jwt.getSubject()).isEqualTo("42");
    }

    @Test
    void acceptsNotBeforeInPast() {
        Jwt jwt = decoder.decode(sign(claimsWithNotBefore(NOW.minusSeconds(60))));

        assertThat(jwt.getSubject()).isEqualTo("42");
    }

    @Test
    void rejectsFutureNotBefore() {
        assertRejected(sign(claimsWithNotBefore(NOW.plusSeconds(1))));
    }

    @Test
    void rejectsInvalidNotBeforeType() throws Exception {
        assertRejected(signRawPayload(rawPayload("\"not-a-number\"")));
    }

    @Test
    void validatorRejectsInvalidNotBeforeTypeWithoutThrowing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("iss", ISSUER)
                .claim("aud", List.of(AUDIENCE))
                .claim("sub", "42")
                .claim("jti", UUID.randomUUID().toString())
                .claim("iat", NOW)
                .claim("exp", NOW.plusSeconds(7200))
                .claim(TOKEN_VERSION_CLAIM, 0)
                .claim("nbf", "not-a-number")
                .build();

        OAuth2TokenValidatorResult result = strictValidator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsFutureIssuedAt() {
        String token = sign(claims("42", UUID.randomUUID().toString(),
                NOW.plusSeconds(1), NOW.plusSeconds(7200), 0));

        assertRejected(token);
    }

    @Test
    void rejectsWrongIssuer() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("another-issuer")
                .subject("42")
                .audience(List.of(AUDIENCE))
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(7200))
                .id(UUID.randomUUID().toString())
                .claim(TOKEN_VERSION_CLAIM, 0)
                .build();

        assertRejected(sign(claims));
    }

    @Test
    void rejectsWrongAudience() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("42")
                .audience(List.of("another-audience"))
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(7200))
                .id(UUID.randomUUID().toString())
                .claim(TOKEN_VERSION_CLAIM, 0)
                .build();

        assertRejected(sign(claims));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub", "jti", "iat", "exp", TOKEN_VERSION_CLAIM, "iss", "aud"})
    void rejectsTokenMissingRequiredClaim(String missingClaim) {
        assertRejected(sign(claimsWithout(missingClaim)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "0001", "9223372036854775808"})
    void rejectsInvalidSubject(String subject) {
        assertRejected(sign(claims(subject, UUID.randomUUID().toString(),
                NOW, NOW.plusSeconds(7200), 0)));
    }

    @Test
    void validatorRejectsNonStringSubjectWithoutThrowing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("iss", ISSUER)
                .claim("aud", List.of(AUDIENCE))
                .claim("sub", 42)
                .claim("jti", UUID.randomUUID().toString())
                .claim("iat", NOW)
                .claim("exp", NOW.plusSeconds(7200))
                .claim(TOKEN_VERSION_CLAIM, 0)
                .build();

        OAuth2TokenValidatorResult result = strictValidator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "123", ""})
    void rejectsInvalidJwtId(String jwtId) {
        assertRejected(sign(claims("42", jwtId, NOW, NOW.plusSeconds(7200), 0)));
    }

    @ParameterizedTest
    @MethodSource("invalidTokenVersions")
    void rejectsInvalidTokenVersion(Object tokenVersion) {
        assertRejected(sign(claims("42", UUID.randomUUID().toString(),
                NOW, NOW.plusSeconds(7200), tokenVersion)));
    }

    static Stream<Arguments> invalidTokenVersions() {
        return Stream.of(
                Arguments.of(-1),
                Arguments.of(2_147_483_648L),
                Arguments.of("1"),
                Arguments.of(1.5d));
    }

    private JwtDecoder decoderAt(Instant instant) {
        return configuration.jwtDecoder(properties, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private String sign(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private static String sign(SecretKey key, MacAlgorithm algorithm, JwtClaimsSet claims) {
        JwtEncoder otherEncoder = NimbusJwtEncoder.withSecretKey(key)
                .algorithm(algorithm)
                .build();
        return otherEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private static JwtClaimsSet validClaims() {
        return claims("42", UUID.randomUUID().toString(), NOW, NOW.plusSeconds(7200), 0);
    }

    private static JwtClaimsSet claims(String subject, String jwtId, Instant issuedAt,
            Instant expiresAt, Object tokenVersion) {
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(List.of(AUDIENCE))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(jwtId)
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
                .build();
    }

    private static JwtClaimsSet claimsWithNotBefore(Instant notBefore) {
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("42")
                .audience(List.of(AUDIENCE))
                .issuedAt(NOW)
                .notBefore(notBefore)
                .expiresAt(NOW.plusSeconds(7200))
                .id(UUID.randomUUID().toString())
                .claim(TOKEN_VERSION_CLAIM, 0)
                .build();
    }

    private static String rawPayload(String notBeforeJson) {
        return String.format(
                "{\"iss\":\"%s\",\"aud\":[\"%s\"],\"sub\":\"42\",\"jti\":\"%s\","
                        + "\"iat\":%d,\"exp\":%d,\"token_version\":0,\"nbf\":%s}",
                ISSUER, AUDIENCE, UUID.randomUUID(), NOW.getEpochSecond(),
                NOW.plusSeconds(7200).getEpochSecond(), notBeforeJson);
    }

    private static String signRawPayload(String payloadJson) throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET_BYTES, "HmacSHA256"));
        byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signature);
    }

    private static JwtClaimsSet claimsWithout(String missingClaim) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder();
        if (!"iss".equals(missingClaim)) {
            builder.issuer(ISSUER);
        }
        if (!"aud".equals(missingClaim)) {
            builder.audience(List.of(AUDIENCE));
        }
        if (!"sub".equals(missingClaim)) {
            builder.subject("42");
        }
        if (!"jti".equals(missingClaim)) {
            builder.id(UUID.randomUUID().toString());
        }
        if (!"iat".equals(missingClaim)) {
            builder.issuedAt(NOW);
        }
        if (!"exp".equals(missingClaim)) {
            builder.expiresAt(NOW.plusSeconds(7200));
        }
        if (!TOKEN_VERSION_CLAIM.equals(missingClaim)) {
            builder.claim(TOKEN_VERSION_CLAIM, 0);
        }
        return builder.build();
    }

    private static AppJwtProperties properties(String secretBase64) {
        AppJwtProperties result = new AppJwtProperties();
        result.setSecretBase64(secretBase64);
        result.setIssuer(ISSUER);
        result.setAudience(AUDIENCE);
        result.setAccessTokenTtl(Duration.ofHours(2));
        return result;
    }

    private static SecretKey secret(byte[] bytes) {
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    private static String encodeSecret(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
