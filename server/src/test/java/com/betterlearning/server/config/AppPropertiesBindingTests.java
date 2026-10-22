package com.betterlearning.server.config;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesBindingTests {

    private static final String VALID_SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    private static final String DB_PASSWORD = "db-password-for-tests";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AppPropertiesConfiguration.class);

    @Test
    void bindsValidPropertiesAndUsesSafeDefaults() {
        runner.withPropertyValues(properties(Map.of())).run(context -> {
            assertThat(context).hasNotFailed();
            AppJwtProperties jwt = context.getBean(AppJwtProperties.class);
            assertThat(jwt.getAccessTokenTtl()).isEqualTo(Duration.ofHours(2));
            assertThat(jwt.getIssuer()).isEqualTo("betterlearning-server");
            assertThat(jwt.getAudience()).isEqualTo("betterlearning-api");
            assertThat(context.getBean(AppAuthProperties.class).getLogin().getIpLimit()).isEqualTo(30);
            assertThat(context.getBean(AppAuthProperties.class).getRegistration().getWindow())
                    .isEqualTo(Duration.ofHours(1));
            assertThat(context.getBean(AppCorsProperties.class).getAllowedOrigins())
                    .containsExactly("http://localhost:3000");
            assertThat(context.getBean(DataSourcePasswordProperties.class).getPassword()).isEqualTo("db-secret");
        });
    }

    @Test
    void missingSecretFailsStartup() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("app.jwt.secret-base64", null);
        assertStartupFails(overrides, "app.jwt.secret-base64");
    }

    @Test
    void shortSecretFailsStartup() {
        assertStartupFails(Map.of(
                "app.jwt.secret-base64", Base64.getEncoder().encodeToString(new byte[16])),
                "至少 32 字节");
    }

    @Test
    void invalidBase64SecretFailsStartup() {
        assertStartupFails(Map.of("app.jwt.secret-base64", "%%%not-base64%%%"), "必须是合法 Base64");
    }

    @Test
    void blankIssuerFailsStartup() {
        assertStartupFails(Map.of("app.jwt.issuer", " "), "app.jwt.issuer 不能为空");
    }

    @Test
    void blankDatasourcePasswordFailsStartup() {
        assertStartupFails(Map.of("spring.datasource.password", ""), "SPRING_DATASOURCE_PASSWORD");
    }

    @Test
    void invalidRateLimitFailsStartup() {
        assertStartupFails(Map.of("app.auth.login.ip-limit", "0"), "app.auth.login.ip-limit 必须为正整数");
    }

    @Test
    void wildcardCorsOriginFailsStartup() {
        assertStartupFails(Map.of("app.cors.allowed-origins", "*"),
                "app.cors.allowed-origins 必须是明确的 http/https origin");
    }

    @Test
    void corsOriginWithPathFailsStartup() {
        assertStartupFails(Map.of("app.cors.allowed-origins", "http://localhost:3000/app"),
                "app.cors.allowed-origins 必须是明确的 http/https origin");
    }

    @Test
    void docsSwitchMapsToSwaggerUiAndApiDocs() {
        assertDocsSwitch("false");
        assertDocsSwitch("true");
    }

    @Test
    void realConfigWithRequiredVariablesStartsSuccessfully() {
        realConfigRunner()
                .withPropertyValues(
                "SPRING_DATASOURCE_PASSWORD=" + DB_PASSWORD,
                "APP_JWT_SECRET_BASE64=" + VALID_SECRET,
                "APP_JWT_ISSUER=betterlearning-server",
                "APP_JWT_AUDIENCE=betterlearning-api")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Environment environment = context.getEnvironment();
                    assertThat(environment.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
                    AppJwtProperties jwt = context.getBean(AppJwtProperties.class);
                    assertThat(jwt.getIssuer()).isEqualTo("betterlearning-server");
                    assertThat(jwt.getAudience()).isEqualTo("betterlearning-api");
                    assertThat(context.getBean(DataSourcePasswordProperties.class).getPassword())
                            .isEqualTo(DB_PASSWORD);
                });
    }

    @Test
    void realConfigWithoutIssuerFailsStartupWithoutLeakingSensitiveValues() {
        realConfigRunner()
                .withPropertyValues(
                "SPRING_DATASOURCE_PASSWORD=" + DB_PASSWORD,
                "APP_JWT_SECRET_BASE64=" + VALID_SECRET,
                "APP_JWT_AUDIENCE=betterlearning-api")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(allMessages(context.getStartupFailure()))
                            .contains("app.jwt.issuer")
                            .doesNotContain(VALID_SECRET)
                            .doesNotContain(DB_PASSWORD);
                });
    }

    @Test
    void realConfigWithoutAudienceFailsStartup() {
        realConfigRunner()
                .withPropertyValues(
                "SPRING_DATASOURCE_PASSWORD=" + DB_PASSWORD,
                "APP_JWT_SECRET_BASE64=" + VALID_SECRET,
                "APP_JWT_ISSUER=betterlearning-server")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(allMessages(context.getStartupFailure())).contains("app.jwt.audience");
                });
    }

    @Test
    void realConfigWithoutDatasourcePasswordFailsStartup() {
        realConfigRunner()
                .withPropertyValues(
                "APP_JWT_SECRET_BASE64=" + VALID_SECRET,
                "APP_JWT_ISSUER=betterlearning-server",
                "APP_JWT_AUDIENCE=betterlearning-api")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(allMessages(context.getStartupFailure()))
                            .contains("SPRING_DATASOURCE_PASSWORD");
                });
    }

    @Test
    void realConfigWithoutJwtSecretFailsStartup() {
        realConfigRunner()
                .withPropertyValues(
                "SPRING_DATASOURCE_PASSWORD=" + DB_PASSWORD,
                "APP_JWT_ISSUER=betterlearning-server",
                "APP_JWT_AUDIENCE=betterlearning-api")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(allMessages(context.getStartupFailure()))
                            .contains("app.jwt.secret-base64 不能为空");
                });
    }

    @Test
    void realConfigWithShortSecretFailsStartup() {
        realConfigRunner()
                .withPropertyValues(
                "SPRING_DATASOURCE_PASSWORD=" + DB_PASSWORD,
                "APP_JWT_SECRET_BASE64=" + Base64.getEncoder().encodeToString(new byte[16]),
                "APP_JWT_ISSUER=betterlearning-server",
                "APP_JWT_AUDIENCE=betterlearning-api")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(allMessages(context.getStartupFailure())).contains("至少 32 字节");
                });
    }

    @Test
    void realConfigWithInvalidBase64SecretFailsStartup() {
        realConfigRunner()
                .withPropertyValues(
                "SPRING_DATASOURCE_PASSWORD=" + DB_PASSWORD,
                "APP_JWT_SECRET_BASE64=%%%not-base64%%%",
                "APP_JWT_ISSUER=betterlearning-server",
                "APP_JWT_AUDIENCE=betterlearning-api")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(allMessages(context.getStartupFailure())).contains("必须是合法 Base64");
                });
    }

    private ApplicationContextRunner realConfigRunner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(AppPropertiesConfiguration.class);
    }

    private void assertDocsSwitch(String value) {
        realConfigRunner()
                .withPropertyValues(
                "SPRING_DATASOURCE_PASSWORD=db-secret",
                "APP_JWT_SECRET_BASE64=" + VALID_SECRET,
                "APP_JWT_ISSUER=betterlearning-server",
                "APP_JWT_AUDIENCE=betterlearning-api",
                "APP_DOCS_ENABLED=" + value)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Environment environment = context.getEnvironment();
                    assertThat(environment.getProperty("springdoc.api-docs.enabled")).isEqualTo(value);
                    assertThat(environment.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(value);
                });
    }

    private void assertStartupFails(Map<String, String> overrides, String expectedMessagePart) {
        runner.withPropertyValues(properties(overrides)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(allMessages(context.getStartupFailure())).contains(expectedMessagePart);
        });
    }

    private static String[] properties(Map<String, String> overrides) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("spring.datasource.password", "db-secret");
        values.put("app.jwt.secret-base64", VALID_SECRET);
        values.put("app.jwt.issuer", "betterlearning-server");
        values.put("app.jwt.audience", "betterlearning-api");
        values.put("app.auth.login.ip-limit", "30");
        values.put("app.auth.login.username-limit", "10");
        values.put("app.auth.login.window", "PT1M");
        values.put("app.auth.registration.ip-limit", "10");
        values.put("app.auth.registration.window", "PT1H");
        values.put("app.cors.allowed-origins", "http://localhost:3000");
        overrides.forEach((key, value) -> {
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
        });
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            messages.append(current.getMessage()).append(' ');
            current = current.getCause();
        }
        return messages.toString();
    }
}
