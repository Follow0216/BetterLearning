package com.betterlearning.server.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 集成测试动态属性注册。
 *
 * <p>只注册各测试类中完全一致的基础设施与限流配置；JWT 的 secret、issuer、audience
 * 由调用方显式传入，CORS origin 由调用方显式列出。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class IntegrationProperties {

    private IntegrationProperties() {
    }

    /**
     * 注册 PostgreSQL 与 Redis 连接属性。
     *
     * @param registry 动态属性注册器
     * @param postgres PostgreSQL 容器
     * @param redis Redis 容器
     */
    public static void registerInfrastructure(DynamicPropertyRegistry registry,
            PostgreSQLContainer postgres, GenericContainer<?> redis) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(IntegrationContainers.REDIS_PORT));
    }

    /**
     * 注册 JWT 属性。
     *
     * @param registry 动态属性注册器
     * @param secretBase64 Base64 编码的 HMAC 密钥
     * @param issuer 签发者
     * @param audience 受众
     */
    public static void registerJwt(DynamicPropertyRegistry registry, String secretBase64,
            String issuer, String audience) {
        registry.add("app.jwt.secret-base64", () -> secretBase64);
        registry.add("app.jwt.issuer", () -> issuer);
        registry.add("app.jwt.audience", () -> audience);
    }

    /**
     * 注册集成测试统一使用的宽松限流值。
     *
     * @param registry 动态属性注册器
     */
    public static void registerRateLimits(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.ip-limit", () -> "1000");
        registry.add("app.auth.login.username-limit", () -> "1000");
        registry.add("app.auth.registration.ip-limit", () -> "1000");
    }

    /**
     * 按数组索引注册允许的 CORS origin。
     *
     * @param registry 动态属性注册器
     * @param origins 允许的 origin，按调用方给定顺序注册
     */
    public static void registerCorsOrigins(DynamicPropertyRegistry registry, String... origins) {
        for (int index = 0; index < origins.length; index++) {
            int propertyIndex = index;
            registry.add("app.cors.allowed-origins[" + propertyIndex + "]",
                    () -> origins[propertyIndex]);
        }
    }
}
