package com.betterlearning.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.betterlearning.server.support.IntegrationContainers;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 验证非 dev profile 不注册、不执行 dev 账号初始化器。
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
class DevUserAccountInitializerNonDevIntegrationTests {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-dev-seed-non-dev-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS =
            IntegrationContainers.redis();

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
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void doesNotRegisterInitializerOrCreateBusinessAccount() {
        assertThat(applicationContext.getBeansOfType(DevUserAccountInitializer.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(DevAdminAccountInitializer.class)).isEmpty();
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user",
                Integer.class);
        assertThat(count).isZero();
    }
}
