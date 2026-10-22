package com.betterlearning.server.config;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesValidationTests {

    private static final String VALID_SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validConfigurationHasNoViolations() {
        assertThat(validator.validate(validJwt())).isEmpty();
        assertThat(validator.validate(validAuth())).isEmpty();
        assertThat(validator.validate(validCors())).isEmpty();
        assertThat(validator.validate(validDataSource())).isEmpty();
    }

    @Test
    void missingSecretIsRejected() {
        AppJwtProperties properties = validJwt();
        properties.setSecretBase64(null);

        assertThat(paths(properties)).contains("secretBase64");
    }

    @Test
    void blankSecretIsRejected() {
        AppJwtProperties properties = validJwt();
        properties.setSecretBase64("   ");

        assertThat(paths(properties)).contains("secretBase64");
    }

    @Test
    void shortSecretIsRejected() {
        AppJwtProperties properties = validJwt();
        properties.setSecretBase64(Base64.getEncoder().encodeToString(new byte[31]));

        assertThat(paths(properties)).contains("secretBase64Valid");
    }

    @Test
    void invalidBase64SecretIsRejected() {
        AppJwtProperties properties = validJwt();
        properties.setSecretBase64("not-base64!!");

        assertThat(paths(properties)).contains("secretBase64Valid");
    }

    @Test
    void blankIssuerAndAudienceAreRejected() {
        AppJwtProperties properties = validJwt();
        properties.setIssuer(" ");
        properties.setAudience("");

        assertThat(paths(properties)).contains("issuer", "audience");
    }

    @Test
    void zeroOrNegativeTtlIsRejected() {
        AppJwtProperties zero = validJwt();
        zero.setAccessTokenTtl(Duration.ZERO);
        AppJwtProperties negative = validJwt();
        negative.setAccessTokenTtl(Duration.ofMinutes(-1));

        assertThat(paths(zero)).contains("accessTokenTtlPositive");
        assertThat(paths(negative)).contains("accessTokenTtlPositive");
    }

    @Test
    void invalidLoginLimitsAreRejected() {
        AppAuthProperties properties = validAuth();
        properties.getLogin().setIpLimit(0);
        properties.getLogin().setUsernameLimit(-1);
        properties.getLogin().setWindow(Duration.ZERO);

        assertThat(paths(properties)).contains("login.ipLimit", "login.usernameLimit", "login.windowPositive");
    }

    @Test
    void invalidRegistrationLimitsAreRejected() {
        AppAuthProperties properties = validAuth();
        properties.getRegistration().setIpLimit(0);
        properties.getRegistration().setWindow(Duration.ofSeconds(-5));

        assertThat(paths(properties)).contains("registration.ipLimit", "registration.windowPositive");
    }

    @Test
    void subMillisecondWindowsAreRejected() {
        AppAuthProperties login = validAuth();
        login.getLogin().setWindow(Duration.ofNanos(999_999));
        AppAuthProperties registration = validAuth();
        registration.getRegistration().setWindow(Duration.ofNanos(1));

        assertThat(paths(login)).contains("login.windowPositive");
        assertThat(paths(registration)).contains("registration.windowPositive");
    }

    @Test
    void overflowingWindowsAreRejected() {
        AppAuthProperties login = validAuth();
        login.getLogin().setWindow(Duration.ofSeconds(Long.MAX_VALUE));
        AppAuthProperties registration = validAuth();
        registration.getRegistration().setWindow(Duration.ofSeconds(Long.MAX_VALUE));

        assertThat(paths(login)).contains("login.windowPositive");
        assertThat(paths(registration)).contains("registration.windowPositive");
    }

    @Test
    void oneMillisecondWindowsAreAccepted() {
        AppAuthProperties properties = validAuth();
        properties.getLogin().setWindow(Duration.ofMillis(1));
        properties.getRegistration().setWindow(Duration.ofMillis(1));

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void emptyOrBlankCorsOriginIsRejected() {
        AppCorsProperties empty = validCors();
        empty.setAllowedOrigins(null);
        AppCorsProperties blank = validCors();
        blank.setAllowedOrigins(List.of(" "));

        assertThat(messages(empty)).contains("app.cors.allowed-origins 至少需要一个来源");
        assertThat(messages(blank)).contains("app.cors.allowed-origins 不能包含空白来源");
    }

    @Test
    void rejectsCorsOriginsThatAreNotExplicitHttpOrigins() {
        List<String> invalid = List.of(
                "*",
                "null",
                "http://localhost:3000/app",
                "http://localhost:3000/",
                "http://user@localhost:3000",
                "http://localhost:3000?debug=1",
                "http://localhost:3000#fragment",
                "ftp://localhost:3000",
                "https://",
                "http://*.example.com",
                "http://localhost:0",
                "http://localhost:70000",
                "http://local host:3000");

        for (String origin : invalid) {
            AppCorsProperties properties = validCors();
            properties.setAllowedOrigins(List.of(origin));
            assertThat(violations(properties))
                    .as("origin %s should be rejected", origin)
                    .isNotEmpty();
        }
    }

    @Test
    void acceptsExplicitHttpOriginsIncludingLocalhostAndIpAddresses() {
        AppCorsProperties properties = validCors();
        properties.setAllowedOrigins(List.of(
                "http://localhost",
                "https://localhost:8443",
                "http://127.0.0.1:3000",
                "http://[::1]:3000",
                "https://example.com",
                "http://192.168.1.10:8080"));

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void blankDatasourcePasswordIsRejected() {
        DataSourcePasswordProperties properties = new DataSourcePasswordProperties();
        properties.setPassword(" ");

        assertThat(paths(properties)).contains("password");
    }

    private Set<String> paths(Object target) {
        return violations(target).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private Set<String> messages(Object target) {
        return violations(target).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private <T> Set<ConstraintViolation<T>> violations(T target) {
        return validator.validate(target);
    }

    private AppJwtProperties validJwt() {
        AppJwtProperties properties = new AppJwtProperties();
        properties.setSecretBase64(VALID_SECRET);
        properties.setIssuer("betterlearning-server");
        properties.setAudience("betterlearning-api");
        properties.setAccessTokenTtl(Duration.ofHours(2));
        return properties;
    }

    private AppAuthProperties validAuth() {
        AppAuthProperties properties = new AppAuthProperties();
        properties.getLogin().setIpLimit(30);
        properties.getLogin().setUsernameLimit(10);
        properties.getLogin().setWindow(Duration.ofMinutes(1));
        properties.getRegistration().setIpLimit(10);
        properties.getRegistration().setWindow(Duration.ofHours(1));
        return properties;
    }

    private AppCorsProperties validCors() {
        AppCorsProperties properties = new AppCorsProperties();
        properties.setAllowedOrigins(List.of("http://localhost:3000"));
        return properties;
    }

    private DataSourcePasswordProperties validDataSource() {
        DataSourcePasswordProperties properties = new DataSourcePasswordProperties();
        properties.setPassword("db-secret");
        return properties;
    }
}
