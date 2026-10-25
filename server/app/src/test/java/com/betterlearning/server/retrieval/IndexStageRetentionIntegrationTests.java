package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Job;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageKey;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageResult;
import com.betterlearning.server.config.MybatisConfiguration;
import com.betterlearning.server.learning.mapper.RetentionMapper;
import com.betterlearning.server.learning.retention.DeletionJournal;
import com.betterlearning.server.learning.retention.SourceDeletionService;
import com.betterlearning.server.learning.retention.SourceRetentionService;
import com.betterlearning.server.learning.service.impl.FileRetentionService;
import com.betterlearning.server.multiagent.MultiAgentModels.Source;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceVersion;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.retrieval.mapper.RetrievalMapper;
import com.betterlearning.server.support.IntegrationContainers;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

/** P6 real PostgreSQL retention of paid-stage evidence without retaining source text or vectors. */
@Testcontainers
@Tag("integration")
class IndexStageRetentionIntegrationTests {
    private static final String SOURCE_SHA = "a".repeat(64);
    private static final String OCR_INPUT_SHA = "b".repeat(64);
    private static final String EMBED_INPUT_SHA = "c".repeat(64);
    private static final String OCR_OUTPUT = "{\"transcript\":\"private handwritten solution\"}";
    private static final String EMBED_OUTPUT = "{\"vectors\":[[1,0]],\"private\":\"cached vector\"}";

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Configuration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = {RetentionMapper.class, RetrievalMapper.class})
    @EnableTransactionManagement
    static class MapperConfiguration {
    }

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static RetentionMapper retentionMapper;
    private static RetrievalIndexStore index;
    private static SourceStore sourceStore;
    private static PlatformTransactionManager manager;
    private static SourceRetentionService retention;

    @BeforeAll
    static void database() {
        JdbcTemplate admin = new JdbcTemplate(dataSource(null));
        admin.execute("CREATE SCHEMA p6_stage_live");
        DataSource source = dataSource("p6_stage_live");
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas("p6_stage_live").defaultSchema("p6_stage_live").load().migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(PlatformTransactionManager.class, () -> manager);
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        sourceStore = mock(SourceStore.class);
        context.registerBean(SourceStore.class, () -> sourceStore);
        context.register(MybatisConfiguration.class, MapperConfiguration.class,
                MybatisPlusAutoConfiguration.class, MybatisRetrievalIndexStore.class);
        context.refresh();
        retentionMapper = context.getBean(RetentionMapper.class);
        index = context.getBean(RetrievalIndexStore.class);
        FileRetentionService files = mock(FileRetentionService.class);
        when(files.pendingFileRefs(100)).thenReturn(List.of());
        retention = new SourceRetentionService(retentionMapper, manager, files);
    }

    @AfterAll
    static void closeDatabase() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void clearDatabase() {
        reset(sourceStore);
        jdbc.execute("TRUNCATE TABLE app_user RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE TABLE ma_source_deletion");
        jdbc.update("UPDATE ma_deletion_replay SET sequence=0,hash=repeat('0',64),replayed_at=NULL");
    }

    @Test
    void withdrawalAndRetentionScrubConfirmedPayloadsButKeepPaidUsageAndFences() throws Exception {
        Fixture fixture = fixture(jdbc, 1, "PROCESSING");
        confirmed(jdbc, fixture, "OCR_STEP", 0, OCR_INPUT_SHA, 0,
                "ocr-model", "handwriting-ocr-v1:math-methods-v1", OCR_OUTPUT, null);
        confirmed(jdbc, fixture, "EMBED_BATCH", 0, EMBED_INPUT_SHA, 2,
                "embedding-model", IndexStagePayloads.EMBEDDING_SCHEMA, EMBED_OUTPUT, 12L);
        intent(jdbc, fixture, "OCR_STEP", 1, "d".repeat(64), 0,
                "ocr-model", "handwriting-ocr-v1:math-methods-v1", "CALL_INTENT");
        intent(jdbc, fixture, "EMBED_BATCH", 1, "e".repeat(64), 2,
                "embedding-model", IndexStagePayloads.EMBEDDING_SCHEMA, "UNKNOWN");
        Path journalRoot = Files.createTempDirectory("p6-stage-delete-");
        SourceDeletionService deletion = deletionService(journalRoot);
        new TransactionTemplate(manager).executeWithoutResult(status ->
                deletion.withdraw(fixture.accountId(), fixture.sourceId()));

        assertScrubbed(jdbc, fixture, 4);
        assertThat(jdbc.queryForObject("SELECT status FROM ma_source WHERE source_id=?",
                String.class, fixture.sourceId())).isEqualTo("DELETED");
        retention.sweep(100);

        assertScrubbed(jdbc, fixture, 4);
        assertThat(jdbc.queryForObject("SELECT database_purged_at IS NOT NULL "
                + "FROM ma_source_deletion WHERE source_id=?", Boolean.class, fixture.sourceId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT purged_at IS NOT NULL FROM ma_source_version "
                + "WHERE source_id=? AND version=1", Boolean.class, fixture.sourceId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id=?",
                Integer.class, fixture.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT embedding_requests FROM ma_index_job WHERE event_id=?",
                Integer.class, fixture.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT embedding_tokens FROM ma_index_job WHERE event_id=?",
                Long.class, fixture.eventId())).isEqualTo(12L);
        assertThat(jdbc.queryForObject("SELECT fence FROM ma_index_job WHERE event_id=?",
                Long.class, fixture.eventId())).isGreaterThanOrEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id=?",
                Boolean.class, fixture.eventId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage WHERE event_id=? "
                + "AND phase='OCR_STEP' AND ordinal=0", String.class, fixture.eventId()))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage WHERE event_id=? "
                + "AND phase='OCR_STEP' AND ordinal=1", String.class, fixture.eventId()))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage WHERE event_id=? "
                + "AND phase='EMBED_BATCH' AND ordinal=1", String.class, fixture.eventId()))
                .isEqualTo("UNKNOWN");
        Job stale = new Job(fixture.eventId(), fixture.accountId(), fixture.sourceId(), 1,
                "SOURCE_VERSION_CREATED", 7, 1);
        StageKey key = new StageKey("OCR_STEP", 0, SOURCE_SHA, fixture.fileRef(), OCR_INPUT_SHA,
                "ocr-model", 0, "handwriting-ocr-v1:math-methods-v1");
        assertThat(index.confirmIndexStage(stale, key,
                new StageResult(OCR_OUTPUT, sha(OCR_OUTPUT), 1, null))).isFalse();
        assertThatThrownBy(() -> index.beginIndexStage(stale, key))
                .isInstanceOfSatisfying(StepExecutionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo("OUTCOME_UNKNOWN"));
        index.unknownIndexStage(stale, key);
        assertScrubbed(jdbc, fixture, 4);
    }

    @Test
    void oldUnreferencedVersionRetiresWithoutPurgingCurrentVersion() {
        Fixture old = fixture(jdbc, 2, "DONE");
        confirmed(jdbc, old, "OCR_STEP", 0, OCR_INPUT_SHA, 0,
                "ocr-model", "handwriting-ocr-v1:math-methods-v1", OCR_OUTPUT, null);
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,"
                + "index_revision,file_ref) VALUES (?,2,?,'READY',1,?)",
                old.sourceId(), "f".repeat(64), UUID.randomUUID().toString());
        jdbc.update("UPDATE ma_source_version SET created_at=CURRENT_TIMESTAMP - INTERVAL '31 days' "
                + "WHERE source_id=? AND version=1", old.sourceId());

        retention.sweep(100);

        assertScrubbed(jdbc, old, 1);
        assertThat(jdbc.queryForObject("SELECT purge_reason FROM ma_source_version "
                + "WHERE source_id=? AND version=1", String.class, old.sourceId()))
                .isEqualTo("OLD_UNREFERENCED");
        assertThat(jdbc.queryForObject("SELECT purged_at FROM ma_source_version "
                + "WHERE source_id=? AND version=2", java.sql.Timestamp.class, old.sourceId())).isNull();
    }

    @Test
    void replayedDeletionJournalScrubsStageEvenWhenOriginalDatabaseDeleteDidNotCommit() throws Exception {
        Fixture fixture = fixture(jdbc, 1, "PROCESSING");
        confirmed(jdbc, fixture, "OCR_STEP", 0, OCR_INPUT_SHA, 0,
                "ocr-model", "handwriting-ocr-v1:math-methods-v1", OCR_OUTPUT, null);
        Path root = Files.createTempDirectory("p6-stage-replay-");
        DeletionJournal journal = DeletionJournal.initialize(root);
        journal.append(fixture.accountId(), fixture.sourceId(), Instant.now());
        assertThat(jdbc.queryForObject("SELECT output_json FROM ma_index_stage WHERE event_id=?",
                String.class, fixture.eventId())).isEqualTo(OCR_OUTPUT);
        SourceDeletionService deletion = new SourceDeletionService(journal, retentionMapper,
                new TransactionTemplate(manager));

        deletion.recoverBeforeServing();
        assertScrubbed(jdbc, fixture, 1);
        deletion.recoverBeforeServing();
        assertScrubbed(jdbc, fixture, 1);
        retention.sweep(100);
        assertThat(jdbc.queryForObject("SELECT database_purged_at IS NOT NULL "
                + "FROM ma_source_deletion WHERE source_id=?", Boolean.class, fixture.sourceId())).isTrue();
    }

    @Test
    void withdrawalHoldingAccountLockDoesNotDeadlockWithStageBeginAndRejectsLateWorker() throws Exception {
        Fixture fixture = fixture(jdbc, 1, "PROCESSING");
        jdbc.update("UPDATE ma_source_version SET status='INDEX_PENDING' WHERE source_id=? AND version=1",
                fixture.sourceId());
        confirmed(jdbc, fixture, "OCR_STEP", 0, OCR_INPUT_SHA, 0,
                "ocr-model", "handwriting-ocr-v1:math-methods-v1", OCR_OUTPUT, null);
        intent(jdbc, fixture, "OCR_STEP", 1, "d".repeat(64), 0,
                "ocr-model", "handwriting-ocr-v1:math-methods-v1", "CALL_INTENT");
        sourceContentForCurrentDatabase(fixture);
        SourceDeletionService deletion = deletionService(Files.createTempDirectory("p6-stage-lock-order-"));
        Job job = new Job(fixture.eventId(), fixture.accountId(), fixture.sourceId(), 1,
                "SOURCE_VERSION_CREATED", 7, 1);
        StageKey key = new StageKey("OCR_STEP", 1, SOURCE_SHA, fixture.fileRef(),
                "d".repeat(64), "ocr-model", 0, "handwriting-ocr-v1:math-methods-v1");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Future<?>> pending = new AtomicReference<>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout='5s'");
                retentionMapper.lockAccount(fixture.accountId());
                pending.set(worker.submit(() -> {
                    started.countDown();
                    return index.beginIndexStage(job, key);
                }));
                awaitAccountLockWait(started);
                deletion.withdraw(fixture.accountId(), fixture.sourceId());
            });
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> pending.get().get(5, TimeUnit.SECONDS));
            assertThat(failure.getCause()).isInstanceOfSatisfying(StepExecutionException.class,
                    outcome -> assertThat(outcome.reason()).isEqualTo("OUTCOME_UNKNOWN"));
            assertScrubbed(jdbc, fixture, 2);
            assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage WHERE event_id=? "
                    + "AND phase='OCR_STEP' AND ordinal=1", String.class, fixture.eventId()))
                    .isEqualTo("UNKNOWN");
            assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id=?",
                    Boolean.class, fixture.eventId())).isTrue();
            retention.sweep(100);
            assertScrubbed(jdbc, fixture, 2);
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void expiredClaimSkipsLockedAccountBeforeTakingJobLockDuringWithdrawal() throws Exception {
        Fixture fixture = fixture(jdbc, 1, "PROCESSING");
        confirmed(jdbc, fixture, "OCR_STEP", 0, OCR_INPUT_SHA, 0,
                "ocr-model", "handwriting-ocr-v1:math-methods-v1", OCR_OUTPUT, null);
        intent(jdbc, fixture, "OCR_STEP", 1, "d".repeat(64), 0,
                "ocr-model", "handwriting-ocr-v1:math-methods-v1", "CALL_INTENT");
        jdbc.update("UPDATE ma_index_job SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second' "
                + "WHERE event_id=?", fixture.eventId());
        SourceDeletionService deletion = deletionService(Files.createTempDirectory("p6-stage-claim-order-"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout='5s'");
                retentionMapper.lockAccount(fixture.accountId());
                Future<?> claim = worker.submit(() -> {
                    started.countDown();
                    return index.claim();
                });
                awaitStarted(started);
                try {
                    assertThat(claim.get(5, TimeUnit.SECONDS)).isEqualTo(java.util.Optional.empty());
                } catch (Exception failure) {
                    throw new IllegalStateException("Expired claim blocked behind account lock", failure);
                }
                assertThat(jdbc.queryForObject("SELECT fence FROM ma_index_job WHERE event_id=?",
                        Long.class, fixture.eventId())).isEqualTo(7L);
                deletion.withdraw(fixture.accountId(), fixture.sourceId());
            });
            assertThat(index.claim()).isEmpty();
            assertScrubbed(jdbc, fixture, 2);
            assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id=?",
                    Boolean.class, fixture.eventId())).isTrue();
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void upgradingAlreadyPurgedV08DatabaseBackfillsSensitiveStageOutputs() {
        JdbcTemplate admin = new JdbcTemplate(dataSource(null));
        admin.execute("CREATE SCHEMA p6_stage_v08_upgrade");
        DataSource legacySource = dataSource("p6_stage_v08_upgrade");
        Flyway.configure().dataSource(legacySource).locations("classpath:db/migration")
                .schemas("p6_stage_v08_upgrade").defaultSchema("p6_stage_v08_upgrade")
                .target(MigrationVersion.fromVersion("2026102508")).load().migrate();
        JdbcTemplate legacy = new JdbcTemplate(legacySource);
        Fixture fixture = fixture(legacy, 1, "PROCESSING");
        confirmed(legacy, fixture, "OCR_STEP", 0, OCR_INPUT_SHA, 0,
                "ocr-model", "handwriting-ocr-v1:math-methods-v1", OCR_OUTPUT, null);
        confirmed(legacy, fixture, "EMBED_BATCH", 0, EMBED_INPUT_SHA, 2,
                "embedding-model", IndexStagePayloads.EMBEDDING_SCHEMA, EMBED_OUTPUT, 12L);
        legacy.queryForObject("SELECT ma_apply_source_deletion(?,?,CURRENT_TIMESTAMP,1,?)",
                Boolean.class, fixture.sourceId(), fixture.accountId(), "f".repeat(64));
        legacy.queryForObject("SELECT ma_scrub_source_version(?,1)",
                Boolean.class, fixture.sourceId());
        legacy.update("UPDATE ma_source_deletion SET database_purged_at=CURRENT_TIMESTAMP "
                + "WHERE source_id=?", fixture.sourceId());
        assertThat(legacy.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=? "
                + "AND output_json IS NOT NULL", Integer.class, fixture.eventId())).isEqualTo(2);

        Flyway.configure().dataSource(legacySource).locations("classpath:db/migration")
                .schemas("p6_stage_v08_upgrade").defaultSchema("p6_stage_v08_upgrade").load().migrate();

        assertScrubbed(legacy, fixture, 2);
        assertThat(legacy.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id=?",
                Integer.class, fixture.eventId())).isEqualTo(1);
        assertThat(legacy.queryForObject("SELECT embedding_tokens FROM ma_index_job WHERE event_id=?",
                Long.class, fixture.eventId())).isEqualTo(12L);
    }

    private SourceDeletionService deletionService(Path journalRoot) throws Exception {
        return new SourceDeletionService(DeletionJournal.initialize(journalRoot), retentionMapper,
                new TransactionTemplate(manager));
    }

    private static void sourceContentForCurrentDatabase(Fixture fixture) {
        when(sourceStore.get(eq(fixture.accountId()), eq(fixture.sourceId()), eq(1L)))
                .thenAnswer(invocation -> {
                    String sourceStatus = jdbc.queryForObject("SELECT status FROM ma_source WHERE source_id=?",
                            String.class, fixture.sourceId());
                    String versionStatus = jdbc.queryForObject("SELECT status FROM ma_source_version "
                            + "WHERE source_id=? AND version=1", String.class, fixture.sourceId());
                    Source source = new Source(fixture.sourceId(), fixture.accountId(), SourceType.SAVED_ANSWER,
                            "question", "private title", sourceStatus, 1, null);
                    SourceVersion version = new SourceVersion(fixture.sourceId(), 1, SOURCE_SHA, versionStatus,
                            "DRAFT", fixture.fileRef(), null, null, 1);
                    return new SourceContent(source, version, List.of());
                });
    }

    private static void awaitAccountLockWait(CountDownLatch started) {
        awaitStarted(started);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE pid<>pg_backend_pid() AND datname=current_database() AND state='active' "
                    + "AND wait_event_type='Lock' AND query LIKE '%app_user%'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("Worker did not wait for the account row lock");
    }

    private static void awaitStarted(CountDownLatch started) {
        try {
            if (!started.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Worker did not start");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for worker", failure);
        }
    }

    private static Fixture fixture(JdbcTemplate target, int currentVersion, String jobStatus) {
        long accountId = target.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES (?, 'hash') RETURNING account_id", Long.class, UUID.randomUUID().toString());
        String sourceId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        String fileRef = UUID.randomUUID().toString();
        target.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?,'SAVED_ANSWER',?,'private title','ACTIVE',?)",
                sourceId, accountId, UUID.randomUUID().toString(), currentVersion);
        target.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,"
                + "index_revision,file_ref) VALUES (?,1,?,'READY',1,?)", sourceId, SOURCE_SHA, fileRef);
        target.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,"
                + "aggregate_version,kind,payload_digest,status,index_revision) "
                + "VALUES (?,?,'SOURCE',?,1,'SOURCE_VERSION_CREATED',?,'PENDING',1)",
                eventId, accountId, sourceId, SOURCE_SHA);
        target.update("INSERT INTO ma_index_job(event_id,status,call_state,fence,lease_until,"
                + "ocr_calls,embedding_requests,embedding_tokens,index_protocol) "
                + "VALUES (? ,?,'NOT_SENT',7,CURRENT_TIMESTAMP + INTERVAL '1 hour',1,1,12,'HANDWRITING_METHODS_V1')",
                eventId, jobStatus);
        return new Fixture(accountId, sourceId, eventId, fileRef);
    }

    private static void confirmed(JdbcTemplate target, Fixture fixture, String phase, int ordinal,
            String inputSha, int dimensions, String model, String schema, String output, Long tokens) {
        stage(target, fixture, phase, ordinal, inputSha, dimensions, model, schema,
                "CONFIRMED", output, sha(output), 1, tokens);
    }

    private static void intent(JdbcTemplate target, Fixture fixture, String phase, int ordinal,
            String inputSha, int dimensions, String model, String schema, String state) {
        stage(target, fixture, phase, ordinal, inputSha, dimensions, model, schema,
                state, null, null, null, null);
    }

    private static void stage(JdbcTemplate target, Fixture fixture, String phase, int ordinal,
            String inputSha, int dimensions, String model, String schema, String state,
            String output, String outputSha, Integer requests, Long tokens) {
        target.update("INSERT INTO ma_index_stage(event_id,phase,ordinal,account_id,source_id,"
                + "source_version,index_revision,source_sha256,file_ref,input_sha256,model,dimensions,"
                + "schema_version,call_state,intent_fence,output_json,output_sha256,request_count,"
                + "total_tokens,confirmed_at) VALUES (?,?,?,?,?,1,1,?,?,?, ?,?,?,?,7,?,?,?,?,"
                + "CASE WHEN ?='CONFIRMED' THEN CURRENT_TIMESTAMP ELSE NULL END)",
                fixture.eventId(), phase, ordinal, fixture.accountId(), fixture.sourceId(),
                SOURCE_SHA, fixture.fileRef(), inputSha, model, dimensions, schema, state,
                output, outputSha, requests, tokens, state);
    }

    private static void assertScrubbed(JdbcTemplate target, Fixture fixture, int count) {
        assertThat(target.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=?",
                Integer.class, fixture.eventId())).isEqualTo(count);
        assertThat(target.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=? "
                + "AND (output_json IS NOT NULL OR output_sha256 IS NOT NULL "
                + "OR input_sha256 IS NOT NULL OR purged_at IS NULL)",
                Integer.class, fixture.eventId())).isZero();
        assertThat(target.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=? "
                + "AND intent_fence=7 AND source_sha256=? AND file_ref=?",
                Integer.class, fixture.eventId(), SOURCE_SHA, fixture.fileRef())).isEqualTo(count);
        assertThat(target.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=? "
                + "AND call_state='CONFIRMED' AND request_count=1",
                Integer.class, fixture.eventId())).isGreaterThanOrEqualTo(1);
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static DataSource dataSource(String schema) {
        String url = POSTGRES.getJdbcUrl();
        if (schema != null) {
            url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record Fixture(long accountId, String sourceId, String eventId, String fileRef) {
    }
}
