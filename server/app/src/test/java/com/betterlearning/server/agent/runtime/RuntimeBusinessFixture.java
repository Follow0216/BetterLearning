package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.MultiAgentModels.CreateRequest;
import com.betterlearning.server.multiagent.MultiAgentModels.Mode;
import com.betterlearning.server.multiagent.MultiAgentModels.Request;
import com.betterlearning.server.multiagent.MultiAgentModels.SnapshotBinding;
import com.betterlearning.server.multiagent.MultiAgentModels.SnapshotInput;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.StepExecutorFactory;
import com.betterlearning.server.multiagent.TutorLedger;
import com.betterlearning.server.learning.retention.DeletionJournal;
import com.betterlearning.server.learning.retention.SourceDeletionService;
import com.betterlearning.server.learning.service.impl.SourceStoreImpl;
import com.betterlearning.server.retrieval.MybatisRetrievalIndexStore;
import com.betterlearning.worker.AgentWorkerApplication;
import com.betterlearning.server.support.IntegrationContainers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P2 故障测试的独立业务库夹具。只创建自己的 PostgreSQL 容器与临时文件根目录；
 * 不复用 Temporal 历史库，也不连接既有业务数据库。
 */
public final class RuntimeBusinessFixture implements AutoCloseable {
    private final PostgreSQLContainer postgres;
    private final Path fixtureRoot;
    private final Path filesRoot;
    private final Path journalRoot;
    private final ConfigurableApplicationContext context;
    private final JdbcTemplate jdbc;
    private final TutorLedger ledger;
    private final SourceStore sources;
    private final StepExecutorFactory executors;

    private RuntimeBusinessFixture() {
        PostgreSQLContainer database = null;
        Path directory = null;
        ConfigurableApplicationContext application = null;
        try {
            directory = Files.createTempDirectory("bl-p2-business-").toAbsolutePath();
            Path files = Files.createDirectory(directory.resolve("files"));
            Path journal = directory.resolve("journal");
            DeletionJournal.initialize(journal);
            String password = UUID.randomUUID().toString().replace("-", "");
            database = IntegrationContainers.postgres()
                    .withDatabaseName("betterlearning_p2")
                    .withUsername("p2_test")
                    .withPassword(password);
            database.start();
            Map<String, Object> properties = Map.ofEntries(
                    Map.entry("spring.datasource.url", database.getJdbcUrl()),
                    Map.entry("spring.datasource.username", database.getUsername()),
                    Map.entry("spring.datasource.password", password),
                    Map.entry("app.files.root", files.toString()),
                    Map.entry("app.agent-retention.delete-log-root", journal.toString()),
                    Map.entry("app.agent-retention.initialize-journal", "false"),
                    Map.entry("app.agent-retention.sweep-enabled", "false"),
                    Map.entry("app.multi-agent.rollout.mode", "V2"),
                    Map.entry("app.multi-agent.rollout.transition-from", "LEGACY"),
                    Map.entry("app.agent.embedding.enabled", "false"),
                    Map.entry("app.agent.workflow.enabled", "false"),
                    Map.entry("app.agent.workflow.worker-enabled", "false"),
                    Map.entry("app.agent.workflow.dispatcher-enabled", "false"),
                    Map.entry("spring.main.banner-mode", "off"));
            MapPropertySource fixtureProperties = new MapPropertySource("p2-fixture", properties);
            ApplicationContextInitializer<ConfigurableApplicationContext> initializer =
                    initialized -> initialized.getEnvironment().getPropertySources().addFirst(fixtureProperties);
            application = new SpringApplicationBuilder(AgentWorkerApplication.class)
                    .web(WebApplicationType.NONE).initializers(initializer).run();
            postgres = database;
            fixtureRoot = directory;
            filesRoot = files;
            journalRoot = journal;
            context = application;
            jdbc = application.getBean(JdbcTemplate.class);
            ledger = application.getBean(TutorLedger.class);
            sources = application.getBean(SourceStore.class);
            if (sources != application.getBean(SourceStoreImpl.class)) {
                throw new IllegalStateException("Worker 未使用生产 SourceStoreImpl");
            }
            application.getBean(SourceDeletionService.class);
            application.getBean(MybatisRetrievalIndexStore.class);
            executors = application.getBean(StepExecutorFactory.class);
        } catch (Exception error) {
            cleanup(application, database, directory, error);
            throw new IllegalStateException("隔离业务 PostgreSQL 测试夹具初始化失败", error);
        }
    }

    /** 启动独立业务库，加载完整 Flyway 迁移与 Worker 非 Web 装配。 */
    public static RuntimeBusinessFixture start() {
        return new RuntimeBusinessFixture();
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public TutorLedger ledger() {
        return ledger;
    }

    public SourceStore sources() {
        return sources;
    }

    public StepExecutorFactory executors() {
        return executors;
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    public Path filesRoot() {
        return filesRoot;
    }

    public Path journalRoot() {
        return journalRoot;
    }

    /** 仅供受控子 JVM 连接本夹具数据库；调用方不得记录凭据。 */
    public String databaseUrl() {
        return postgres.getJdbcUrl();
    }

    public String databaseUsername() {
        return postgres.getUsername();
    }

    /** 仅供受控子 JVM 连接本夹具数据库；调用方不得记录或序列化返回值。 */
    public String databasePassword() {
        return postgres.getPassword();
    }

    public record Seed(long accountId, String questionId, Request request) { }

    /**
     * 与 P1 TutorLedgerIntegrationTests 相同的账本种子。fileRef 仅为账本引用，
     * 本方法不生成可由 SnapshotPort 读取的真实冻结文件。
     */
    public Seed createQueuedRun() {
        String username = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Long account = jdbc.queryForObject("INSERT INTO app_user(username, password_hash) VALUES (?, ?) "
                + "RETURNING account_id", Long.class, username, "test-hash");
        if (account == null) {
            throw new IllegalStateException("测试账号未创建");
        }
        Long question = jdbc.queryForObject("INSERT INTO question(account_id, request_id, subject, "
                + "body_markdown, summary) VALUES (?, ?, 'OTHER', '题目', '题目') RETURNING question_id",
                Long.class, account, UUID.randomUUID().toString());
        if (question == null) {
            throw new IllegalStateException("测试题目未创建");
        }
        String questionId = Long.toString(question);
        String requestId = UUID.randomUUID().toString();
        ledger.create(account, questionId, requestId, new CreateRequest(requestId, "请提示解法",
                Mode.HINT, new SnapshotInput("NEW_CAPTURE", null)));
        String fileRef = UUID.randomUUID().toString();
        Request request = ledger.bindSnapshot(account, questionId, requestId,
                new SnapshotBinding(fileRef, digest(fileRef), UUID.randomUUID().toString(), 1));
        if (request.status() != Status.QUEUED || request.currentRunId() == null) {
            throw new IllegalStateException("测试 run 未进入排队状态");
        }
        return new Seed(account, questionId, request);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private static void cleanup(ConfigurableApplicationContext application, PostgreSQLContainer database,
            Path directory, Throwable original) {
        if (application != null) {
            try {
                application.close();
            } catch (RuntimeException error) {
                original.addSuppressed(error);
            }
        }
        if (database != null) {
            try {
                database.stop();
            } catch (RuntimeException error) {
                original.addSuppressed(error);
            }
        }
        if (directory != null) {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException error) {
                original.addSuppressed(error);
            }
        }
    }

    @Override
    public void close() {
        RuntimeException cleanupError = new IllegalStateException("隔离业务夹具清理失败");
        cleanup(context, postgres, fixtureRoot, cleanupError);
        if (cleanupError.getSuppressed().length > 0) {
            throw cleanupError;
        }
    }
}
