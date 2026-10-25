package com.betterlearning.operations;

import java.io.PrintStream;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.betterlearning.server.learning.mapper.LegacyGenerationRecoveryMapper;
import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService;
import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService.Command;
import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService.Observation;
import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService.Result;
import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService.Target;

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Manual recovery entry point. It does not create a Spring application context. */
public final class LegacyGenerationRecoveryCommand {
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "--account-id", "--question-id", "--turn-id", "--expected-file-ref",
            "--expected-updated-at", "--operation-id", "--operator", "--incident");
    private static final Set<String> FLAGS = Set.of(
            "--confirm-executors-stopped", "--accept-unknown-outcome");
    private static final Set<String> REJECTION_CODES = Set.of(
            "DRAIN_REQUIRED", "TARGET_NOT_FOUND", "OPERATION_CONFLICT", "AUDIT_STATE_MISMATCH",
            "OBSERVATION_CHANGED",
            "QUIET_PERIOD_REQUIRED", "INVALID_TARGET", "INVALID_COMMAND",
            "EXPLICIT_CONFIRMATIONS_REQUIRED");

    private LegacyGenerationRecoveryCommand() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        return run(args, env, out, err, LegacyGenerationRecoveryCommand::open);
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err,
            GatewayFactory gatewayFactory) {
        final Parsed parsed;
        try {
            parsed = parse(args);
        } catch (IllegalArgumentException exception) {
            err.println("E_ARGUMENT");
            return 2;
        }
        try {
            validateEnvironment(env);
        } catch (IllegalArgumentException exception) {
            err.println("E_CONFIG");
            return 3;
        }
        try {
            Gateway gateway = gatewayFactory.open(env);
            if (parsed.command() == null) {
                Observation value = gateway.inspect(parsed.target());
                out.printf("accountId=%d questionId=%d turnId=%s mode=%s status=%s fileRef=%s "
                        + "updatedAt=%s observedAt=%s eligibleAt=%s%n",
                        value.target().accountId(), value.target().questionId(), value.target().turnId(),
                        safeMetadata(value.mode()), safeMetadata(value.status()), safeMetadata(value.fileRef()),
                        nullable(value.updatedAt()), nullable(value.observedAt()), nullable(value.eligibleAt()));
            } else {
                Result value = gateway.apply(parsed.command());
                out.printf("operationId=%s turnId=%s status=%s alreadyApplied=%s%n",
                        safeMetadata(value.operationId()), safeMetadata(value.turnId()),
                        safeMetadata(value.status()), value.alreadyApplied());
            }
            return 0;
        } catch (IllegalStateException exception) {
            String code = exception.getMessage();
            err.println(code != null && REJECTION_CODES.contains(code) ? "E_" + code : "E_EXECUTION");
            return 4;
        } catch (Exception exception) {
            err.println("E_EXECUTION");
            return 4;
        }
    }

    private static Parsed parse(String[] args) {
        if (args == null || args.length == 0
                || (!"inspect".equals(args[0]) && !"apply".equals(args[0]))) {
            throw new IllegalArgumentException();
        }
        boolean apply = "apply".equals(args[0]);
        Map<String, String> values = new HashMap<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int index = 1; index < args.length; index++) {
            String name = args[index];
            if (!seen.add(name)) {
                throw new IllegalArgumentException();
            }
            if (FLAGS.contains(name)) {
                if (!apply) {
                    throw new IllegalArgumentException();
                }
            } else if (VALUE_OPTIONS.contains(name)) {
                if (++index >= args.length || args[index].startsWith("--")) {
                    throw new IllegalArgumentException();
                }
                values.put(name, args[index]);
            } else {
                throw new IllegalArgumentException();
            }
        }
        if (!values.keySet().containsAll(Set.of("--account-id", "--question-id", "--turn-id"))) {
            throw new IllegalArgumentException();
        }
        Target target = new Target(positive(values.get("--account-id")),
                positive(values.get("--question-id")), uuid(values.get("--turn-id")));
        if (!apply) {
            if (values.size() != 3 || !seen.equals(Set.of("--account-id", "--question-id", "--turn-id"))) {
                throw new IllegalArgumentException();
            }
            return new Parsed(target, null);
        }
        if (values.size() != VALUE_OPTIONS.size() || !seen.containsAll(VALUE_OPTIONS)
                || !seen.containsAll(FLAGS)) {
            throw new IllegalArgumentException();
        }
        Instant expectedUpdatedAt;
        try {
            expectedUpdatedAt = Instant.parse(values.get("--expected-updated-at"));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException();
        }
        Command command = new Command(target, uuid(values.get("--expected-file-ref")), expectedUpdatedAt,
                uuid(values.get("--operation-id")), token(values.get("--operator")),
                token(values.get("--incident")), true, true);
        return new Parsed(target, command);
    }

    private static long positive(String raw) {
        if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException();
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException();
        }
    }

    private static String uuid(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException();
        }
        try {
            UUID parsed = UUID.fromString(raw);
            if (!parsed.toString().equals(raw)) {
                throw new IllegalArgumentException();
            }
            return raw;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException();
        }
    }

    private static String token(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9._@:/-]{1,120}")) {
            throw new IllegalArgumentException();
        }
        return raw;
    }

    private static void validateEnvironment(Map<String, String> env) {
        if (env == null) {
            throw new IllegalArgumentException();
        }
        String url = env.get("SPRING_DATASOURCE_URL");
        String username = env.get("SPRING_DATASOURCE_USERNAME");
        String password = env.get("SPRING_DATASOURCE_PASSWORD");
        if (url == null || !url.startsWith("jdbc:postgresql://")
                || username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException();
        }
        try {
            URI parsed = URI.create(url.substring("jdbc:".length()));
            if (!"postgresql".equals(parsed.getScheme()) || parsed.getHost() == null
                    || parsed.getPath() == null || parsed.getPath().length() < 2
                    || parsed.getUserInfo() != null || parsed.getFragment() != null
                    || parsed.getRawQuery() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException();
        }
    }

    private static Gateway open(Map<String, String> env) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(env.get("SPRING_DATASOURCE_URL"));
        dataSource.setUsername(env.get("SPRING_DATASOURCE_USERNAME"));
        dataSource.setPassword(env.get("SPRING_DATASOURCE_PASSWORD"));

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setCacheEnabled(false);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setDefaultExecutorType(ExecutorType.SIMPLE);
        configuration.setLogImpl(NoLoggingImpl.class);
        configuration.addMapper(LegacyGenerationRecoveryMapper.class);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.afterPropertiesSet();
        SqlSessionFactory sessionFactory = factory.getObject();
        if (sessionFactory == null) {
            throw new IllegalStateException();
        }
        LegacyGenerationRecoveryMapper mapper =
                new SqlSessionTemplate(sessionFactory).getMapper(LegacyGenerationRecoveryMapper.class);
        LegacyGenerationRecoveryService service = new LegacyGenerationRecoveryService(
                mapper, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        return new Gateway() {
            @Override
            public Observation inspect(Target target) {
                return service.inspect(target);
            }

            @Override
            public Result apply(Command command) {
                return service.apply(command);
            }
        };
    }

    private static String nullable(Object value) {
        return value == null ? "-" : value.toString();
    }

    private static String safeMetadata(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}") ? value : "-";
    }

    private record Parsed(Target target, Command command) {
    }

    interface Gateway {
        Observation inspect(Target target);

        Result apply(Command command);
    }

    @FunctionalInterface
    interface GatewayFactory {
        Gateway open(Map<String, String> env) throws Exception;
    }
}
