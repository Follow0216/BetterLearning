package com.betterlearning.server.learning.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import com.betterlearning.server.agent.service.TutoringModel;
import com.betterlearning.operations.LegacyGenerationRecoveryCommand;
import com.betterlearning.server.learning.mapper.LegacyGenerationRecoveryMapper;
import com.betterlearning.server.learning.mapper.QuestionCollectionMapper;
import com.betterlearning.server.learning.mapper.QuestionMapper;
import com.betterlearning.server.learning.mapper.RolloutGateMapper;
import com.betterlearning.server.learning.mapper.TutorTurnMapper;
import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.dao.TutorTurnDao;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService.Command;
import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService.Observation;
import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService.Target;
import com.betterlearning.server.learning.service.FrozenAnswerService;
import com.betterlearning.server.learning.service.RolloutGate;
import com.betterlearning.server.learning.service.TutorTurnService;
import com.betterlearning.server.learning.service.impl.TutorTurnServiceImpl;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;
import com.betterlearning.server.support.TestHttpClient;

import jakarta.servlet.http.Part;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockPart;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

import tools.jackson.databind.json.JsonMapper;

/** The clock seam advances recovery eligibility; database claim timestamps and state are never fabricated. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LegacyGenerationRecoveryIntegrationTests.Models.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class LegacyGenerationRecoveryIntegrationTests {
    private static final String PASSWORD = "Learn-Pass123";
    private static final String BODY = "求导 x²";
    private static final String PROMPT = "仅用于恢复测试的辅导正文";
    private static final String QUESTIONS = "/api/v1/learning/questions";
    private static final Path FILE_ROOT = temporaryRoot();
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-legacy-recovery-test-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();
    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "legacy-recovery-test", "legacy-recovery-client");
        IntegrationProperties.registerRateLimits(registry);
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
        registry.add("app.files.root", FILE_ROOT::toString);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JsonMapper json;
    @Autowired
    private FrozenAnswerService frozen;
    @Autowired
    private TutorTurnService turns;
    @Autowired
    private TutorTurnMapper turnMapper;
    @Autowired
    private LegacyGenerationRecoveryMapper recoveryMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private RolloutGate rollout;
    @Autowired
    private ModelCalls modelCalls;

    private final AtomicReference<Instant> simulatedNow = new AtomicReference<>();
    private LegacyGenerationRecoveryService recovery;
    private TestHttpClient http;

    @BeforeEach
    void clean() {
        http = new TestHttpClient(port, json);
        TestData.clean(jdbc, redis);
        rollout.switchTo("LEGACY");
        simulatedNow.set(null);
        recovery = new LegacyGenerationRecoveryService(recoveryMapper,
                new TransactionTemplate(transactionManager), () -> simulatedNow.get());
        modelCalls.count.set(0);
    }

    @AfterAll
    static void removeOwnedFiles() throws IOException {
        try (var paths = Files.walk(FILE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void inspectIsReadOnlyAndRecoveryNeedsDrainConfirmationsAndFullQuietPeriod() throws Exception {
        Fixture fixture = generating("recovery_owner");
        simulatedNow.set(fixture.claimedAt());
        Observation seen = recovery.inspect(fixture.target());
        assertThat(seen.status()).isEqualTo("GENERATING");
        assertThat(seen.fileRef()).isEqualTo(fixture.fileRef());
        assertThat(seen.updatedAt()).isEqualTo(fixture.claimedAt());
        assertThat(seen.eligibleAt()).isEqualTo(fixture.claimedAt().plus(LegacyGenerationRecoveryService.QUIET_PERIOD));
        assertThat(seen.toString()).doesNotContain(PROMPT, BODY);
        assertThat(auditCount()).isZero();

        Command command = command(fixture, UUID.randomUUID().toString(), "INC-QUIET", true, true);
        rejected("DRAIN_REQUIRED", () -> recovery.apply(command));
        rollout.switchTo("DRAIN");
        rejected("EXPLICIT_CONFIRMATIONS_REQUIRED", () -> recovery.apply(
                command(fixture, UUID.randomUUID().toString(), "INC-QUIET", false, true)));
        rejected("EXPLICIT_CONFIRMATIONS_REQUIRED", () -> recovery.apply(
                command(fixture, UUID.randomUUID().toString(), "INC-QUIET", true, false)));
        rejected("QUIET_PERIOD_REQUIRED", () -> recovery.apply(command));
        simulatedNow.set(seen.eligibleAt().minusNanos(1));
        rejected("QUIET_PERIOD_REQUIRED", () -> recovery.apply(command));
        assertThat(auditCount()).isZero();
        assertThat(turns.get(fixture.account().id(), fixture.question(), fixture.turn()).getStatus())
                .isEqualTo("GENERATING");

        // This is a test-only clock simulation, not an actual ten-minute wait.
        simulatedNow.set(seen.eligibleAt());
        assertThat(recovery.apply(command)).satisfies(result -> {
            assertThat(result.status()).isEqualTo(LegacyGenerationRecoveryService.UNKNOWN);
            assertThat(result.alreadyApplied()).isFalse();
        });
        assertThat(auditCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT database_principal FROM ma_legacy_generation_recovery "
                + "WHERE operation_id=?", String.class, command.operationId()))
                .isEqualTo(jdbc.queryForObject("SELECT current_user", String.class));
        assertThat(jdbc.queryForObject("SELECT operator_ref FROM ma_legacy_generation_recovery "
                + "WHERE operation_id=?", String.class, command.operationId())).isEqualTo("release.operator");
        assertThat(jdbc.queryForObject("SELECT file_ref FROM tutor_turn WHERE turn_id=?", String.class,
                fixture.turn())).isEqualTo(fixture.fileRef());
        assertThat(http.jsonBody(http.get(fixture.path(), fixture.account().token())))
                .containsEntry("status", "FAILED").containsEntry("failureCode", LegacyGenerationRecoveryService.UNKNOWN)
                .containsEntry("fileRef", fixture.fileRef()).containsEntry("answerMarkdown", null);
        assertThat(http.jsonBody(http.get(fixture.path() + "/answer", fixture.account().token())))
                .containsEntry("saveRequestId", fixture.turn());
        assertThat(download(fixture.path() + "/answer/files/" + fixture.fileRef() + "/blocks/0",
                fixture.account().token())).isEqualTo(png(32));
        assertThat(recovery.apply(command).alreadyApplied()).isTrue();
        rejected("OPERATION_CONFLICT", () -> recovery.apply(new Command(fixture.target(), fixture.fileRef(),
                fixture.claimedAt(), command.operationId(), "release.operator", "INC-DIFFERENT", true, true)));
        assertThat(auditCount()).isEqualTo(1);

        assertThat(turns.finish(fixture.account().id(), fixture.question(), fixture.turn(), fixture.fileRef(),
                "late answer", null, null).getFailureCode()).isEqualTo(LegacyGenerationRecoveryService.UNKNOWN);
        assertThat(turns.finish(fixture.account().id(), fixture.question(), fixture.turn(), fixture.fileRef(),
                null, "LATE_FAILURE", "late failure").getFailureCode()).isEqualTo(LegacyGenerationRecoveryService.UNKNOWN);
        assertThat(turnMapper.claim(fixture.account().id(), Long.parseLong(fixture.question()), fixture.turn(),
                "FAILED")).isZero();
        // An older binary's unguarded retry statement must be rejected by the migration trigger.
        assertThatThrownBy(() -> jdbc.update("UPDATE tutor_turn SET status='GENERATING', answer_markdown=NULL,"
                + "failure_code=NULL, failure_message=NULL, updated_at=CURRENT_TIMESTAMP "
                + "WHERE account_id=? AND question_id=? AND turn_id=? AND status='FAILED' AND file_ref IS NOT NULL",
                fixture.account().id(), Long.parseLong(fixture.question()), fixture.turn()))
                .isInstanceOf(DataAccessException.class);
        assertThat(http.jsonBody(http.get(fixture.path(), fixture.account().token())))
                .containsEntry("status", "FAILED").containsEntry("failureCode", LegacyGenerationRecoveryService.UNKNOWN);
        rollout.switchFrom("DRAIN", "LEGACY");
        http.assertErrorEnvelope(http.post(fixture.path() + "/retry", "{}", fixture.account().token()),
                409, "A0455");
        assertThat(modelCalls.count.get()).isZero();
        assertThat(http.jsonBody(http.get(fixture.path(), fixture.account().token())))
                .containsEntry("failureCode", LegacyGenerationRecoveryService.UNKNOWN)
                .containsEntry("answerMarkdown", null);
    }

    @Test
    void wrongOwnershipFileAndObservationCannotRecoverAndFinishWinnerStaysFinal() throws Exception {
        Fixture fixture = generating("recovery_primary");
        Account other = account("recovery_other");
        rollout.switchTo("DRAIN");
        simulatedNow.set(fixture.claimedAt().plus(LegacyGenerationRecoveryService.QUIET_PERIOD));
        Command valid = command(fixture, UUID.randomUUID().toString(), "INC-STALE", true, true);
        Target foreignTarget = new Target(other.id(), Long.parseLong(fixture.question()), fixture.turn());
        rejected("TARGET_NOT_FOUND", () -> recovery.inspect(foreignTarget));
        rejected("TARGET_NOT_FOUND", () -> recovery.apply(new Command(foreignTarget, fixture.fileRef(),
                fixture.claimedAt(), UUID.randomUUID().toString(), "release.operator", "INC-STALE", true, true)));
        rejected("OBSERVATION_CHANGED", () -> recovery.apply(new Command(fixture.target(), UUID.randomUUID().toString(),
                fixture.claimedAt(), UUID.randomUUID().toString(), "release.operator", "INC-STALE", true, true)));
        rejected("OBSERVATION_CHANGED", () -> recovery.apply(new Command(fixture.target(), fixture.fileRef(),
                fixture.claimedAt().minusNanos(1), UUID.randomUUID().toString(),
                "release.operator", "INC-STALE", true, true)));
        assertThat(auditCount()).isZero();
        assertThat(turns.finish(fixture.account().id(), fixture.question(), fixture.turn(), fixture.fileRef(),
                "winner answer", null, null).getStatus()).isEqualTo("SUCCEEDED");
        rejected("OBSERVATION_CHANGED", () -> recovery.apply(valid));
        assertThat(auditCount()).isZero();
        assertThat(http.jsonBody(http.get(fixture.path(), fixture.account().token())))
                .containsEntry("status", "SUCCEEDED").containsEntry("answerMarkdown", "winner answer");
        assertThat(modelCalls.count.get()).isZero();
    }

    @Test
    void committedClaimSurvivesARealChildJvmCrashAndRequiresAuditedDrainRecovery() throws Exception {
        Account owner = account("crash_owner");
        String question = question(owner);
        String root = QUESTIONS + "/" + question + "/tutor-turns";
        HttpResponse<String> created = http.post(root, json.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "prompt", PROMPT)), owner.token());
        assertThat(created.statusCode()).isEqualTo(200);
        String turn = (String) http.jsonBody(created).get("turnId");
        AnswerModels.SaveManifest manifest = manifest(turn);
        String ref = frozen.accept(owner.id(), question, turn, manifest, List.of(
                part("manifest", json.writeValueAsBytes(manifest), MediaType.APPLICATION_JSON),
                part("chunk_0000", png(32), MediaType.IMAGE_PNG),
                part("question_0000", png(16), MediaType.IMAGE_PNG))).turn().getFileRef();

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
                LegacyGenerationClaimCrashMain.class.getName()).redirectErrorStream(true);
        Map<String, String> childEnvironment = builder.environment();
        Map<String, String> essentials = new HashMap<>();
        for (String name : List.of("PATH", "HOME", "TMPDIR", "LANG")) {
            if (childEnvironment.containsKey(name)) {
                essentials.put(name, childEnvironment.get(name));
            }
        }
        childEnvironment.clear();
        childEnvironment.putAll(essentials);
        builder.environment().put("BL_CHILD_JDBC_URL", POSTGRES.getJdbcUrl());
        builder.environment().put("BL_CHILD_JDBC_USER", POSTGRES.getUsername());
        builder.environment().put("BL_CHILD_JDBC_PASSWORD", POSTGRES.getPassword());
        builder.environment().put("BL_CHILD_ACCOUNT_ID", Long.toString(owner.id()));
        builder.environment().put("BL_CHILD_QUESTION_ID", question);
        builder.environment().put("BL_CHILD_TURN_ID", turn);
        Process child = builder.start();
        String childOutput;
        try {
            assertThat(child.waitFor(30, TimeUnit.SECONDS)).as("child JVM must exit after committed claim").isTrue();
            childOutput = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(child.exitValue()).as(childOutput).isEqualTo(73);
            assertThat(childOutput).contains("CLAIM_COMMITTED");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
        assertThat(modelCalls.count.get()).isZero();
        assertThat(http.jsonBody(http.get(root + "/" + turn, owner.token())))
                .containsEntry("status", "GENERATING").containsEntry("fileRef", ref);
        Target target = new Target(owner.id(), Long.parseLong(question), turn);
        simulatedNow.set(Instant.now());
        Observation observed = recovery.inspect(target);
        assertThat(observed.status()).isEqualTo("GENERATING");
        rollout.switchTo("DRAIN");
        assertThatThrownBy(() -> rollout.switchFrom("DRAIN", "V2"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("仍有活动辅导任务");
        simulatedNow.set(observed.updatedAt().plus(Duration.ofMinutes(10)));
        Command command = new Command(target, ref, observed.updatedAt(), UUID.randomUUID().toString(),
                "release.operator", "INC-CRASH", true, true);
        assertThat(recovery.apply(command).status()).isEqualTo(LegacyGenerationRecoveryService.UNKNOWN);
        assertThat(turns.finish(owner.id(), question, turn, ref, "late answer", null, null).getFailureCode())
                .isEqualTo(LegacyGenerationRecoveryService.UNKNOWN);
        assertThat(download(root + "/" + turn + "/answer/files/" + ref + "/blocks/0", owner.token()))
                .isEqualTo(png(32));
        assertThat(auditCount()).isEqualTo(1);
        rollout.switchFrom("DRAIN", "V2");
    }

    @Test
    void productionCliUsesRealMapperForInspectAndRejectsAnEarlyApply() throws Exception {
        Fixture fixture = generating("cli_real_mapper");
        Map<String, String> env = Map.of(
                "SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl().split("\\?", 2)[0],
                "SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername(),
                "SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        String[] target = {"--account-id", Long.toString(fixture.account().id()),
                "--question-id", fixture.question(), "--turn-id", fixture.turn()};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        try (var out = new java.io.PrintStream(output, true, StandardCharsets.UTF_8);
                var err = new java.io.PrintStream(error, true, StandardCharsets.UTF_8)) {
            assertThat(LegacyGenerationRecoveryCommand.run(new String[]{"inspect", target[0], target[1],
                    target[2], target[3], target[4], target[5]}, env, out, err)).isZero();
            assertThat(output.toString(StandardCharsets.UTF_8))
                    .contains("status=GENERATING", "fileRef=" + fixture.fileRef())
                    .doesNotContain(PROMPT, BODY);
            assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
            rollout.switchTo("DRAIN");
            output.reset();
            String[] apply = {"apply", target[0], target[1], target[2], target[3], target[4], target[5],
                    "--expected-file-ref", fixture.fileRef(), "--expected-updated-at",
                    fixture.claimedAt().toString(), "--operation-id", UUID.randomUUID().toString(),
                    "--operator", "release.operator", "--incident", "INC-CLI-EARLY",
                    "--confirm-executors-stopped", "--accept-unknown-outcome"};
            assertThat(LegacyGenerationRecoveryCommand.run(apply, env, out, err)).isEqualTo(4);
            assertThat(error.toString(StandardCharsets.UTF_8)).contains("E_QUIET_PERIOD_REQUIRED");
            assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
        }
        assertThat(auditCount()).isZero();
        assertThat(turns.get(fixture.account().id(), fixture.question(), fixture.turn()).getStatus())
                .isEqualTo("GENERATING");
    }

    @Test
    void sharedOperationIdAuditConflictRollsBackTheLosersTurnTransition() throws Exception {
        Fixture first = generating("audit_one");
        Fixture second = generating("audit_two");
        rollout.switchTo("DRAIN");
        Instant eligible = first.claimedAt().isAfter(second.claimedAt())
                ? first.claimedAt() : second.claimedAt();
        Instant afterQuiet = eligible.plus(LegacyGenerationRecoveryService.QUIET_PERIOD).plusSeconds(1);
        CyclicBarrier bothReadMissingAudit = new CyclicBarrier(2);
        LegacyGenerationRecoveryService contending = new LegacyGenerationRecoveryService(recoveryMapper,
                new TransactionTemplate(transactionManager), () -> {
                    try {
                        bothReadMissingAudit.await(10, TimeUnit.SECONDS);
                        return afterQuiet;
                    } catch (Exception failure) {
                        throw new IllegalStateException("TEST_BARRIER_FAILED", failure);
                    }
                });
        String sharedOperation = UUID.randomUUID().toString();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstApply = workers.submit(() -> contending.apply(
                    command(first, sharedOperation, "INC-AUDIT-RACE", true, true)));
            Future<?> secondApply = workers.submit(() -> contending.apply(
                    command(second, sharedOperation, "INC-AUDIT-RACE", true, true)));
            int applied = 0;
            int rejected = 0;
            for (Future<?> attempt : List.of(firstApply, secondApply)) {
                try {
                    attempt.get(15, TimeUnit.SECONDS);
                    applied++;
                } catch (ExecutionException conflict) {
                    rejected++;
                }
            }
            assertThat(applied).isEqualTo(1);
            assertThat(rejected).isEqualTo(1);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(auditCount()).isEqualTo(1);
        List<String> statuses = List.of(first, second).stream()
                .map(fixture -> turns.get(fixture.account().id(), fixture.question(), fixture.turn()).getStatus())
                .toList();
        assertThat(statuses).containsExactlyInAnyOrder("FAILED", "GENERATING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tutor_turn WHERE status='FAILED' "
                + "AND failure_code='GENERATION_OUTCOME_UNKNOWN'", Long.class)).isEqualTo(1);
        assertThat(modelCalls.count.get()).isZero();
    }

    private Fixture generating(String username) throws Exception {
        Account owner = account(username);
        String question = question(owner);
        String root = QUESTIONS + "/" + question + "/tutor-turns";
        HttpResponse<String> created = http.post(root, json.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "prompt", PROMPT)), owner.token());
        assertThat(created.statusCode()).isEqualTo(200);
        String turn = (String) http.jsonBody(created).get("turnId");
        AnswerModels.SaveManifest manifest = manifest(turn);
        String ref = frozen.accept(owner.id(), question, turn, manifest, List.of(
                part("manifest", json.writeValueAsBytes(manifest), MediaType.APPLICATION_JSON),
                part("chunk_0000", png(32), MediaType.IMAGE_PNG),
                part("question_0000", png(16), MediaType.IMAGE_PNG))).turn().getFileRef();
        assertThat(turns.claim(owner.id(), question, turn, false).claimed()).isTrue();
        Instant claimedAt = turns.get(owner.id(), question, turn).getUpdatedAt().toInstant();
        assertThat(claimedAt).isNotNull();
        return new Fixture(owner, question, turn, ref, claimedAt, root + "/" + turn);
    }

    private Command command(Fixture fixture, String operation, String incident, boolean stopped, boolean unknown) {
        return new Command(fixture.target(), fixture.fileRef(), fixture.claimedAt(), operation,
                "release.operator", incident, stopped, unknown);
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM ma_legacy_generation_recovery", Long.class);
    }

    private Account account(String username) throws Exception {
        assertThat(http.post("/api/v1/users", TestData.registerJson(username, PASSWORD)).statusCode()).isEqualTo(201);
        HttpResponse<String> login = http.post("/api/v1/sessions", TestData.loginJson(username, PASSWORD));
        assertThat(login.statusCode()).isEqualTo(201);
        long id = jdbc.queryForObject("SELECT account_id FROM app_user WHERE username=?", Long.class, username);
        return new Account(id, (String) http.jsonBody(login).get("accessToken"));
    }

    private String question(Account account) throws Exception {
        HttpResponse<String> response = http.post(QUESTIONS, json.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "subject", "OTHER", "bodyMarkdown", BODY)), account.token());
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(response).get("questionId");
    }

    private byte[] download(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(http.uri(path))
                .header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static AnswerModels.SaveManifest manifest(String turn) throws IOException {
        AnswerModels.Block composite = block("chunk_0000", 32, png(32));
        AnswerModels.Block raster = block("question_0000", 16, png(16));
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout(
                AnswerManifestValidator.sha256(BODY.getBytes(StandardCharsets.UTF_8)), 16.0, 16.0,
                "local-markdown-v1", new AnswerModels.Bounds(0.0, 0.0, 16.0, 16.0),
                List.of(new AnswerModels.LayoutBlock("body-0", 0.0, 0.0, 16.0, 16.0)), List.of(raster));
        AnswerModels.Stroke stroke = new AnswerModels.Stroke("s-1", "#152744", 2.0,
                List.of(new AnswerModels.Point(-2.0, -2.0), new AnswerModels.Point(1.0, 1.0)));
        return new AnswerModels.SaveManifest(1, turn, null,
                new AnswerModels.Canvas(32.0, 32.0, -8.0, -8.0, "logical"),
                new AnswerModels.Document(1, layout, List.of(stroke)), List.of(composite));
    }

    private static AnswerModels.Block block(String name, int size, byte[] bytes) {
        return new AnswerModels.Block(0, name, 0.0, 0.0, (double) size, (double) size,
                size, size, AnswerManifestValidator.sha256(bytes));
    }

    private static byte[] png(int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff152744);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        image.flush();
        return bytes.toByteArray();
    }

    private static Part part(String name, byte[] bytes, MediaType media) {
        MockPart part = new MockPart(name, name, bytes);
        part.getHeaders().setContentType(media);
        return part;
    }

    private static void rejected(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(IllegalStateException.class,
                failure -> assertThat(failure).hasMessage(code));
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("betterlearning-legacy-recovery-it-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Account(long id, String token) { }

    private record Fixture(Account account, String question, String turn, String fileRef,
            Instant claimedAt, String path) {
        Target target() {
            return new Target(account.id(), Long.parseLong(question), turn);
        }
    }

    static final class ModelCalls {
        final AtomicInteger count = new AtomicInteger();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Models {
        @Bean
        ModelCalls modelCalls() {
            return new ModelCalls();
        }

        @Bean
        @Primary
        TutoringModel countingModel(ModelCalls calls) {
            return (prompt, snapshot) -> {
                calls.count.incrementAndGet();
                return "unexpected model invocation";
            };
        }
    }
}

/** Test-only subprocess. It commits a real V1 claim and halts before any model call. */
final class LegacyGenerationClaimCrashMain {
    private LegacyGenerationClaimCrashMain() { }

    public static void main(String[] args) {
        try {
            claim(System.getenv());
            System.out.println("CLAIM_COMMITTED");
            System.out.flush();
            Runtime.getRuntime().halt(73);
        } catch (Exception failure) {
            System.err.println("CLAIM_FAILED");
            System.exit(2);
        }
    }

    private static void claim(Map<String, String> env) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(env.get("BL_CHILD_JDBC_URL"));
        dataSource.setUsername(env.get("BL_CHILD_JDBC_USER"));
        dataSource.setPassword(env.get("BL_CHILD_JDBC_PASSWORD"));

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setDefaultExecutorType(ExecutorType.SIMPLE);
        configuration.setLogImpl(NoLoggingImpl.class);
        configuration.addMapper(QuestionMapper.class);
        configuration.addMapper(QuestionCollectionMapper.class);
        configuration.addMapper(TutorTurnMapper.class);
        configuration.addMapper(RolloutGateMapper.class);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setMapperLocations(
                new ClassPathResource("mapper/learning/QuestionMapper.xml"),
                new ClassPathResource("mapper/learning/QuestionCollectionMapper.xml"),
                new ClassPathResource("mapper/learning/TutorTurnMapper.xml"));
        factory.afterPropertiesSet();
        SqlSessionFactory sessions = factory.getObject();
        if (sessions == null) {
            throw new IllegalStateException("MYBATIS_UNAVAILABLE");
        }
        SqlSessionTemplate sql = new SqlSessionTemplate(sessions);
        TutorTurnServiceImpl turns = new TutorTurnServiceImpl(
                new LearningDao(sql.getMapper(QuestionMapper.class), sql.getMapper(QuestionCollectionMapper.class)),
                new TutorTurnDao(sql.getMapper(TutorTurnMapper.class)),
                new RolloutGate(sql.getMapper(RolloutGateMapper.class)));
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        long account = Long.parseLong(env.get("BL_CHILD_ACCOUNT_ID"));
        String question = env.get("BL_CHILD_QUESTION_ID");
        String turn = env.get("BL_CHILD_TURN_ID");
        transaction.executeWithoutResult(status -> {
            if (!turns.claim(account, question, turn, false).claimed()) {
                throw new IllegalStateException("CLAIM_NOT_APPLIED");
            }
        });
    }
}
