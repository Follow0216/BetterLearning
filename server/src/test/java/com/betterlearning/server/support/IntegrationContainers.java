package com.betterlearning.server.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试容器工厂。
 *
 * <p>每次调用都返回新的容器实例，由各测试类继续通过 {@code @Container} 管理生命周期；
 * 不提供全局共享容器。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class IntegrationContainers {

    /** Redis 容器暴露的端口。 */
    public static final int REDIS_PORT = 6379;

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.3")
            .asCompatibleSubstituteFor("postgres");

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4.2");

    private IntegrationContainers() {
    }

    /**
     * 创建新的 PostgreSQL 容器。
     *
     * @return 尚未启动的 PostgreSQL 容器
     */
    public static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("betterlearning")
                .withUsername("test")
                .withPassword("test");
    }

    /**
     * 创建新的 Redis 容器。
     *
     * @return 尚未启动的 Redis 容器
     */
    public static GenericContainer<?> redis() {
        return new GenericContainer<>(REDIS_IMAGE)
                .withExposedPorts(REDIS_PORT);
    }
}
