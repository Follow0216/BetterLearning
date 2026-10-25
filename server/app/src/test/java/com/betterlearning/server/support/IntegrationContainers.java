package com.betterlearning.server.support;

import java.util.UUID;

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

    private static final String CONTAINER_PREFIX_PROPERTY = "betterlearning.test.container-prefix";
    private static final String TEST_RUN_LABEL = "com.betterlearning.test-run";

    private IntegrationContainers() {
    }

    /**
     * 创建新的 PostgreSQL 容器。
     *
     * @return 尚未启动的 PostgreSQL 容器
     */
    public static PostgreSQLContainer postgres() {
        PostgreSQLContainer container = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("betterlearning")
                .withUsername("test")
                .withPassword("test");
        isolate(container, "pg");
        return container;
    }

    /**
     * 创建新的 Redis 容器。
     *
     * @return 尚未启动的 Redis 容器
     */
    public static GenericContainer<?> redis() {
        GenericContainer<?> container = new GenericContainer<>(REDIS_IMAGE)
                .withExposedPorts(REDIS_PORT);
        isolate(container, "redis");
        return container;
    }

    private static void isolate(GenericContainer<?> container, String kind) {
        String prefix = System.getProperty(CONTAINER_PREFIX_PROPERTY);
        if (prefix == null) {
            return;
        }
        if (prefix.length() > 63 || !prefix.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid test container prefix");
        }
        String name = prefix + "-" + kind + "-" + UUID.randomUUID();
        container.withCreateContainerCmdModifier(command -> command.withName(name));
        container.withLabel(TEST_RUN_LABEL, prefix);
    }
}
