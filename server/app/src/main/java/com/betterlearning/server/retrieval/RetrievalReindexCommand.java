package com.betterlearning.server.retrieval;

import java.io.IOException;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.betterlearning.server.config.MybatisConfiguration;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.config.AppFilesProperties;
import com.betterlearning.server.learning.config.LearningMapperConfiguration;
import com.betterlearning.server.learning.mapper.RetentionMapper;
import com.betterlearning.server.learning.retention.DeletionJournal;
import com.betterlearning.server.learning.retention.SourceDeletionService;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.service.impl.SourceStoreImpl;
import com.betterlearning.server.learning.validation.SourceAnchorValidator;
import com.betterlearning.server.multiagent.SourceStore;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/** 显式运维命令；只建立事实源所需的 MyBatis 上下文，不启动 Web、worker 或模型客户端。 */
public final class RetrievalReindexCommand {
    private RetrievalReindexCommand() {
    }

    /** Registered only by the CLI's private context; API component scan must never see this bean. */
    @EnableTransactionManagement
    static class ReindexConfiguration {
        @Bean(initMethod = "recoverBeforeServing")
        SourceDeletionService sourceDeletionService(ConfigurationValues configuration, RetentionMapper mapper,
                PlatformTransactionManager manager) throws IOException {
            return new SourceDeletionService(openJournal(configuration), mapper, new TransactionTemplate(manager));
        }
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.getenv(), System.out, System.err));
    }

    static int execute(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
        try {
            Options options = Options.parse(args);
            ConfigurationValues configuration = ConfigurationValues.read(environment);
            try (AnnotationConfigApplicationContext context = context(configuration)) {
                var updated = context.getBean(SourceStore.class).reindex(options.accountId(),
                        options.sourceId(), options.version(), options.expectedIndexRevision());
                out.println("REINDEX_QUEUED source=" + options.sourceId() + " version="
                        + options.version() + " indexRevision=" + updated.indexRevision());
                return 0;
            }
        } catch (CommandFailure exception) {
            err.println(exception.code() + ": " + exception.safeMessage());
            return 2;
        } catch (BusinessException exception) {
            String code = exception.getErrorCode() == ErrorCode.NOT_FOUND ? "SOURCE_NOT_FOUND"
                    : exception.getErrorCode() == ErrorCode.ANSWER_CHANGED ? "SOURCE_STALE" : "SOURCE_REJECTED";
            err.println(code);
            return 3;
        } catch (RuntimeException exception) {
            err.println("REINDEX_UNAVAILABLE");
            return 4;
        }
    }

    private static AnnotationConfigApplicationContext context(ConfigurationValues configuration) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        DataSource dataSource = new DriverManagerDataSource(configuration.url(),
                configuration.username(), configuration.password());
        AppFilesProperties files = new AppFilesProperties();
        files.setRoot(configuration.filesRoot().toString());
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.registerBean(AppFilesProperties.class, () -> files);
        context.registerBean(ConfigurationValues.class, () -> configuration);
        context.register(MybatisConfiguration.class, LearningMapperConfiguration.class,
                MybatisPlusAutoConfiguration.class, ReindexConfiguration.class,
                AnswerFileStore.class, SourceAnchorValidator.class, SourceStoreImpl.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException exception) {
            context.close();
            throw exception;
        }
    }

    /** Same fail-closed path and real-path separation rules as AgentRetentionConfiguration, without scheduling. */
    private static DeletionJournal openJournal(ConfigurationValues configuration) throws IOException {
        Path root = configuration.journalRoot().normalize();
        Path objects = configuration.filesRoot().normalize();
        if (!root.isAbsolute() || root.startsWith(objects) || objects.startsWith(root)) {
            throw new IllegalStateException("Deletion journal must be independent of APP_FILES_ROOT");
        }
        // Never initialize or reconstruct a missing journal from a maintenance command.
        DeletionJournal journal = new DeletionJournal(root);
        Path realRoot = root.toRealPath();
        Path realObjects = objects.toRealPath();
        if (realRoot.startsWith(realObjects) || realObjects.startsWith(realRoot)) {
            throw new IllegalStateException("Deletion journal must use an independent directory");
        }
        return journal;
    }

    private record ConfigurationValues(String url, String username, String password,
            Path filesRoot, Path journalRoot) {
        static ConfigurationValues read(Map<String, String> environment) {
            String url = environment.get("SPRING_DATASOURCE_URL");
            String username = environment.get("SPRING_DATASOURCE_USERNAME");
            String password = environment.get("SPRING_DATASOURCE_PASSWORD");
            String rootValue = environment.get("APP_FILES_ROOT");
            String journalValue = environment.get("APP_AGENT_RETENTION_DELETE_LOG_ROOT");
            if (url == null || !url.startsWith("jdbc:postgresql:") || username == null
                    || username.isBlank() || password == null || password.isBlank() || rootValue == null
                    || journalValue == null || journalValue.isBlank()) {
                throw new CommandFailure("CONFIG_INVALID", "需要 PostgreSQL 连接、APP_FILES_ROOT 与独立删除日志");
            }
            final Path root;
            try {
                root = Path.of(rootValue);
            } catch (IllegalArgumentException exception) {
                throw new CommandFailure("FILES_ROOT_INVALID", "APP_FILES_ROOT 必须是已存在的可写真实目录");
            }
            if (!root.isAbsolute() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(root) || !Files.isWritable(root)) {
                throw new CommandFailure("FILES_ROOT_INVALID", "APP_FILES_ROOT 必须是已存在的可写真实目录");
            }
            final Path journal;
            try {
                journal = Path.of(journalValue).normalize();
            } catch (IllegalArgumentException exception) {
                throw new CommandFailure("JOURNAL_ROOT_INVALID", "独立删除日志路径不合法");
            }
            if (!journal.isAbsolute() || journal.startsWith(root) || root.startsWith(journal)) {
                throw new CommandFailure("JOURNAL_ROOT_INVALID", "独立删除日志路径不可与文件根重叠");
            }
            return new ConfigurationValues(url, username, password, root, journal);
        }
    }

    private record Options(long accountId, String sourceId, long version, long expectedIndexRevision) {
        static Options parse(String[] args) {
            if (args == null) {
                throw new CommandFailure("ARGUMENT_INVALID", "必须指定账号、源、版本和索引修订");
            }
            Map<String, String> values = new HashMap<>();
            boolean confirmed = false;
            for (int index = 0; index < args.length; index++) {
                String option = args[index];
                if ("--confirm-new-paid-attempt".equals(option)) {
                    if (confirmed) {
                        throw new CommandFailure("ARGUMENT_INVALID", "参数不可重复");
                    }
                    confirmed = true;
                } else if ("--account".equals(option) || "--source".equals(option)
                        || "--version".equals(option) || "--expected-index-revision".equals(option)) {
                    if (index + 1 >= args.length || values.putIfAbsent(option, args[++index]) != null) {
                        throw new CommandFailure("ARGUMENT_INVALID", "参数缺失或重复");
                    }
                } else {
                    throw new CommandFailure("ARGUMENT_INVALID", "未知参数");
                }
            }
            if (!confirmed) {
                throw new CommandFailure("CONFIRM_REQUIRED",
                        "新索引事件可能产生 OCR 或 embedding 费用；须指定 --confirm-new-paid-attempt");
            }
            try {
                long accountId = Long.parseLong(values.get("--account"));
                long version = Long.parseLong(values.get("--version"));
                long revision = Long.parseLong(values.get("--expected-index-revision"));
                String source = values.get("--source");
                if (accountId < 1 || version < 1 || revision < 1 || source == null
                        || !UUID.fromString(source).toString().equals(source)) {
                    throw new IllegalArgumentException("Invalid command arguments");
                }
                return new Options(accountId, source, version, revision);
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new CommandFailure("ARGUMENT_INVALID", "账号、源 UUID、版本或索引修订无效");
            }
        }
    }

    private static final class CommandFailure extends RuntimeException {
        private final String code;
        private final String safeMessage;

        private CommandFailure(String code, String safeMessage) {
            this.code = code;
            this.safeMessage = safeMessage;
        }

        String code() {
            return code;
        }

        String safeMessage() {
            return safeMessage;
        }
    }
}
