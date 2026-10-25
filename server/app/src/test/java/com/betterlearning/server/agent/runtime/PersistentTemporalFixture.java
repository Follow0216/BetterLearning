package com.betterlearning.server.agent.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 使用独立 PostgreSQL 保存历史的 Temporal Server 集成测试夹具。
 * 三个容器和网络均由本实例创建；不连接项目数据库或已有 Docker 容器。
 */
public final class PersistentTemporalFixture implements AutoCloseable {
    private static final String POSTGRES_IMAGE = "postgres:16.6@sha256:"
            + "557fea37a744d5f4c8faab304b0a90858b53ab119735a88c131fd19dab802f36";
    private static final String ADMIN_IMAGE = "temporalio/admin-tools:1.30.2@sha256:"
            + "024c6473df113e4b220b3caf6056d30964582ffcae6f6e46a1074aa6c67968d3";
    private static final String SERVER_IMAGE = "temporalio/server:1.30.2@sha256:"
            + "d5334ee3ddce1617efbe280a10afc85916cf8d81798415c98988dbda2b46773e";
    private static final String POSTGRES_ALIAS = "temporal-pg";
    private static final String DYNAMIC_CONFIG = "limit.maxIDLength:\n"
            + "  - value: 255\n"
            + "    constraints: {}\n"
            + "system.forceSearchAttributesCacheRefreshOnRead:\n"
            + "  - value: true\n"
            + "    constraints: {}\n";
    private static final int GRPC_PORT = 7233;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final Network network;
    private final PostgreSQLContainer postgres;
    private final GenericContainer<?> server;
    private final DockerClient docker;
    private String target;
    private WorkflowServiceStubs service;
    private WorkflowClient client;

    private PersistentTemporalFixture() {
        String password = UUID.randomUUID().toString().replace("-", "");
        network = Network.newNetwork();
        postgres = new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("temporal_bootstrap")
                .withUsername("temporal")
                .withPassword(password)
                .withNetwork(network)
                .withNetworkAliases(POSTGRES_ALIAS);
        server = new GenericContainer<>(DockerImageName.parse(SERVER_IMAGE))
                .withCreateContainerCmdModifier(command -> command.withName(
                        "bl-p2-temporal-server-" + UUID.randomUUID()))
                .withNetwork(network)
                .withExposedPorts(GRPC_PORT)
                .withEnv("DB", "postgres12")
                .withEnv("DB_PORT", "5432")
                .withEnv("POSTGRES_SEEDS", POSTGRES_ALIAS)
                .withEnv("POSTGRES_USER", "temporal")
                .withEnv("POSTGRES_PWD", password)
                .withEnv("BIND_ON_IP", "0.0.0.0")
                .withEnv("DYNAMIC_CONFIG_FILE_PATH", "/tmp/p2-dynamicconfig.yaml")
                .withCopyToContainer(Transferable.of(DYNAMIC_CONFIG.getBytes(StandardCharsets.UTF_8)),
                        "/tmp/p2-dynamicconfig.yaml")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(READY_TIMEOUT));
        docker = DockerClientFactory.instance().client();
        try {
            postgres.start();
            initializeSchema(password);
            server.start();
            target = currentTarget();
            connectWithin(READY_TIMEOUT);
        } catch (Exception error) {
            try {
                close();
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw new IllegalStateException("隔离 Temporal/PostgreSQL 测试服务启动失败", error);
        }
    }

    /** 启动隔离 PostgreSQL、schema 工具与 Temporal Server。 */
    public static PersistentTemporalFixture start() {
        return new PersistentTemporalFixture();
    }

    private void initializeSchema(String password) throws Exception {
        GenericContainer<?> admin = new GenericContainer<>(DockerImageName.parse(ADMIN_IMAGE))
                .withCreateContainerCmdModifier(command -> command.withName(
                "bl-p2-temporal-schema-" + UUID.randomUUID()).withEntrypoint("/bin/sh"))
                .withCommand("-c", "sleep 300")
                .withNetwork(network)
                .withEnv("SQL_PASSWORD", password);
        try {
            admin.start();
            String sql = "temporal-sql-tool --plugin postgres12 --ep " + POSTGRES_ALIAS
                    + " -u temporal -p 5432 --db ";
            String temporalSchema = "/etc/temporal/schema/postgresql/v12/temporal/versioned";
            String visibilitySchema = "/etc/temporal/schema/postgresql/v12/visibility/versioned";
            String script = "set -eu\n"
                    + sql + "temporal create\n"
                    + sql + "temporal setup-schema -v 0.0\n"
                    + sql + "temporal update-schema -d " + temporalSchema + "\n"
                    + sql + "temporal_visibility create\n"
                    + sql + "temporal_visibility setup-schema -v 0.0\n"
                    + sql + "temporal_visibility update-schema -d " + visibilitySchema + "\n";
            var result = admin.execInContainer("/bin/sh", "-ec", script);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Temporal schema 初始化失败，退出码 "
                        + result.getExitCode() + ": " + result.getStderr());
            }
        } finally {
            admin.stop();
        }
    }

    public String serverImage() {
        return SERVER_IMAGE;
    }

    public String postgresImage() {
        return POSTGRES_IMAGE;
    }

    public String target() {
        return target;
    }

    public String serverContainerId() {
        return server.getContainerId();
    }

    public WorkflowServiceStubs service() {
        return service;
    }

    public WorkflowClient client() {
        return client;
    }

    /** 保留 PostgreSQL 容器，只停止、重启同一 Temporal Server 容器。 */
    public void restartServer() {
        String id = server.getContainerId();
        if (id == null || !isRunning(id)) {
            throw new IllegalStateException("Temporal Server 测试容器未运行");
        }
        disconnect();
        docker.stopContainerCmd(id).withTimeout(10).exec();
        if (isRunning(id)) {
            throw new IllegalStateException("Temporal Server 停止后仍处于运行状态");
        }
        docker.startContainerCmd(id).exec();
        if (!isRunning(id)) {
            throw new IllegalStateException("Temporal Server 重启后未处于运行状态");
        }
        target = currentTarget();
        connectWithin(READY_TIMEOUT);
    }

    private String currentTarget() {
        Ports.Binding[] bindings = docker.inspectContainerCmd(server.getContainerId()).exec()
                .getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(GRPC_PORT));
        if (bindings == null || bindings.length == 0 || bindings[0] == null) {
            throw new IllegalStateException("Temporal Server 无 gRPC host 端口映射");
        }
        return server.getHost() + ":" + bindings[0].getHostPortSpec();
    }

    private boolean isRunning(String id) {
        return Boolean.TRUE.equals(docker.inspectContainerCmd(id).exec().getState().getRunning());
    }

    private void connectWithin(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException lastError = null;
        do {
            if (!isRunning(server.getContainerId())) {
                throw new IllegalStateException("Temporal Server 在就绪前退出");
            }
            WorkflowServiceStubs candidate = null;
            try {
                candidate = WorkflowServiceStubs.newConnectedServiceStubs(
                        WorkflowServiceStubsOptions.newBuilder().setTarget(target).build(), CONNECT_TIMEOUT);
                service = candidate;
                client = WorkflowClient.newInstance(candidate);
                return;
            } catch (RuntimeException error) {
                lastError = error;
                if (candidate != null) {
                    candidate.shutdown();
                }
            }
            try {
                TimeUnit.MILLISECONDS.sleep(250);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Temporal Server 就绪时被中断", error);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Temporal Server 未在期限内就绪: " + target, lastError);
    }

    private void disconnect() {
        client = null;
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Override
    public void close() {
        disconnect();
        server.stop();
        postgres.stop();
        network.close();
    }
}
