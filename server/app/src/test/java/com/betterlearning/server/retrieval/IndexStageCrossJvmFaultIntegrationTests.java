package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;
import javax.imageio.ImageIO;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Chunk;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Job;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageKey;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageResult;
import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.config.HandwritingMethodsProperties;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.retrieval.handwriting.HandwritingOcrValidator;
import com.betterlearning.server.agent.retrieval.handwriting.HandwritingMethodMetadata;
import com.betterlearning.server.agent.retrieval.ranking.HandwritingMethodTaxonomy;
import com.betterlearning.server.agent.service.HandwritingIndexOcr;
import com.betterlearning.server.config.MybatisConfiguration;
import com.betterlearning.server.learning.config.LearningMapperConfiguration;
import com.betterlearning.server.learning.retention.DeletionJournal;
import com.betterlearning.server.learning.retention.SourceDeletionService;
import com.betterlearning.server.learning.service.impl.SourceStoreImpl;
import com.betterlearning.server.learning.validation.SourceAnchorValidator;
import com.betterlearning.server.retrieval.HandwritingSourceReader.AnchorDraft;
import com.betterlearning.server.retrieval.HandwritingSourceReader.PreparedStep;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.support.IntegrationContainers;
import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

/** Draft only: eight bounded cross-JVM stage and publication checks; no external provider. */
@Testcontainers
@Tag("integration")
class IndexStageCrossJvmFaultIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();
    private static JdbcTemplate jdbc;
    private static HttpServer paidStub;
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger OCR_CALLS = new AtomicInteger();
    private static final AtomicInteger EMBEDDING_CALLS = new AtomicInteger();
    private static String stubUrl;

    @TempDir(cleanup = CleanupMode.NEVER)
    Path logs;

    private long account;
    private String source;
    private String event;
    private String file;
    private final List<ChildJvm> children = new ArrayList<>();

    @BeforeAll
    static void infrastructure() throws IOException {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        paidStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        paidStub.createContext("/paid", exchange -> {
            CALLS.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        paidStub.createContext("/ocr", exchange -> {
            OCR_CALLS.incrementAndGet();
            CALLS.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = IndexStageFaultChildMain.ocrOutput().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        paidStub.createContext("/embeddings", exchange -> {
            EMBEDDING_CALLS.incrementAndGet();
            CALLS.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"data\":[{\"index\":0,\"embedding\":[1,0]}],"
                    + "\"model\":\"unit-model\",\"usage\":{\"prompt_tokens\":3,"
                    + "\"total_tokens\":7}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        paidStub.start();
        stubUrl = "http://127.0.0.1:" + paidStub.getAddress().getPort() + "/paid";
    }

    @AfterAll
    static void closeInfrastructure() {
        if (paidStub != null) {
            paidStub.stop(0);
        }
    }

    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE TABLE app_user RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE TABLE ma_source_deletion");
        jdbc.update("UPDATE ma_deletion_replay SET sequence=0,hash=repeat('0',64),replayed_at=NULL");
        CALLS.set(0);
        OCR_CALLS.set(0);
        EMBEDDING_CALLS.set(0);
        account = jdbc.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES (?,?) RETURNING account_id", Long.class,
                UUID.randomUUID().toString().replace("-", ""), "test");
        source = UUID.randomUUID().toString();
        event = UUID.randomUUID().toString();
        file = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?,'SAVED_ANSWER',?,'answer','ACTIVE',1)", source, account, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,index_revision,file_ref) "
                + "VALUES (?,1,?,'INDEX_PENDING',1,?)", source, "a".repeat(64), file);
        jdbc.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,"
                + "aggregate_version,kind,payload_digest,status,index_revision) "
                + "VALUES (?,?,'SOURCE',?,1,'SOURCE_VERSION_CREATED',?,'PENDING',1)",
                event, account, source, "a".repeat(64));
    }

    @AfterEach
    void cleanupChildren() throws Exception {
        Exception aggregated = null;
        for (ChildJvm child : children) {
            if (child.process().isAlive()) {
                try {
                    stopChild(child, "AFTER_EACH_TERMINATED");
                } catch (Exception failure) {
                    if (aggregated == null) {
                        aggregated = new IOException("One or more child JVMs were not cleaned up");
                    }
                    aggregated.addSuppressed(failure);
                }
            }
        }
        if (aggregated != null) {
            throw aggregated;
        }
    }

    @Test
    void confirmedOcrIsReusedAfterSeparateJvmRestart() throws Exception {
        child("ocr-confirm");
        assertThat(CALLS.get()).isEqualTo(1);
        expireLease();
        child("ocr-resume-confirmed");
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id=?",
                Integer.class, event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT embedding_requests FROM ma_index_job WHERE event_id=?",
                Integer.class, event)).isZero();
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage WHERE event_id=?",
                String.class, event)).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmedEmbeddingIsReusedAfterSeparateJvmRestart() throws Exception {
        child("embed-confirm");
        assertThat(CALLS.get()).isEqualTo(1);
        expireLease();
        child("embed-resume-confirmed");
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT embedding_requests FROM ma_index_job WHERE event_id=?",
                Integer.class, event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id=?",
                Integer.class, event)).isZero();
        assertThat(jdbc.queryForObject("SELECT embedding_tokens FROM ma_index_job WHERE event_id=?",
                Long.class, event)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage WHERE event_id=?",
                String.class, event)).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmedOcrAndEmbeddingPublishExactlyOneReadyVersionAfterRestarts() throws Exception {
        child("ocr-confirm");
        assertThat(CALLS.get()).isEqualTo(1);
        expireLease();
        child("embed-confirm");
        assertThat(CALLS.get()).isEqualTo(2);
        expireLease();
        child("publish-confirmed");
        assertThat(CALLS.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_source_version "
                + "WHERE source_id=? AND status='READY' AND index_revision=1", Integer.class,
                source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_source_version WHERE source_id=?",
                Integer.class, source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_anchor WHERE source_id=?",
                Integer.class, source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_retrieval_chunk WHERE source_id=?",
                Integer.class, source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ma_index_job WHERE event_id=?",
                String.class, event)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT status FROM ma_outbox WHERE event_id=?",
                String.class, event)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id=?",
                Integer.class, event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT embedding_requests FROM ma_index_job WHERE event_id=?",
                Integer.class, event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT embedding_tokens FROM ma_index_job WHERE event_id=?",
                Long.class, event)).isEqualTo(7L);
        child("claim-empty");
        assertThat(CALLS.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_source_version WHERE source_id=?",
                Integer.class, source)).isEqualTo(1);
    }

    @Test
    void structuredIndexerRecoversBothConfirmedPhasesAcrossJvmRestarts() throws Exception {
        child("structured-ocr-crash");
        assertThat(Files.readString(logs.resolve("structured-ocr-crash-confirmation.marker")))
                .contains("phase=OCR_STEP");
        assertThat(OCR_CALLS.get()).isEqualTo(1);
        assertThat(EMBEDDING_CALLS.get()).isZero();
        expireLease();
        child("structured-embed-crash");
        assertThat(Files.readString(logs.resolve("structured-embed-crash-confirmation.marker")))
                .contains("phase=EMBED_BATCH");
        assertThat(OCR_CALLS.get()).isEqualTo(1);
        assertThat(EMBEDDING_CALLS.get()).isEqualTo(1);
        expireLease();
        child("structured-publish");
        assertThat(OCR_CALLS.get()).isEqualTo(1);
        assertThat(EMBEDDING_CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_source_version "
                + "WHERE source_id=? AND status='READY' AND index_revision=1", Integer.class,
                source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_source_version WHERE source_id=?",
                Integer.class, source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_anchor WHERE source_id=?",
                Integer.class, source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_retrieval_chunk WHERE source_id=?",
                Integer.class, source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ma_index_job WHERE event_id=?",
                String.class, event)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT status FROM ma_outbox WHERE event_id=?",
                String.class, event)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id=?",
                Integer.class, event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT embedding_requests FROM ma_index_job WHERE event_id=?",
                Integer.class, event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT embedding_tokens FROM ma_index_job WHERE event_id=?",
                Long.class, event)).isEqualTo(7L);
        assertThat(CALLS.get()).isEqualTo(2);
    }

    @Test
    void unknownOcrAfterCrashCannotIssueSecondPaidCall() throws Exception {
        child("ocr-intent-only");
        assertThat(CALLS.get()).isEqualTo(1);
        expireLease();
        child("ocr-resume-unknown");
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage WHERE event_id=?",
                String.class, event)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id=?",
                Boolean.class, event)).isTrue();
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM ma_index_job WHERE event_id=?",
                String.class, event)).isEqualTo("OUTCOME_UNKNOWN");
        assertUnknownHasNoPublishedIndex();
        expireLease();
        child("ocr-resume-unknown");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void unknownEmbeddingAfterCrashCannotIssueSecondPaidCall() throws Exception {
        child("embed-intent-only");
        assertThat(CALLS.get()).isEqualTo(1);
        expireLease();
        child("embed-resume-unknown");
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage WHERE event_id=?",
                String.class, event)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id=?",
                Boolean.class, event)).isTrue();
        assertThat(jdbc.queryForObject("SELECT embedding_requests FROM ma_index_job WHERE event_id=?",
                Integer.class, event)).isZero();
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM ma_index_job WHERE event_id=?",
                String.class, event)).isEqualTo("OUTCOME_UNKNOWN");
        assertUnknownHasNoPublishedIndex();
        expireLease();
        child("embed-resume-unknown");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    private void assertUnknownHasNoPublishedIndex() {
        assertThat(jdbc.queryForObject("SELECT status FROM ma_index_job WHERE event_id=?",
                String.class, event)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM ma_outbox WHERE event_id=?",
                String.class, event)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_source_version "
                + "WHERE source_id=? AND status='READY'", Integer.class, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_retrieval_chunk WHERE source_id=?",
                Integer.class, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_anchor WHERE source_id=?",
                Integer.class, source)).isZero();
    }

    @Test
    void originalFenceCannotConfirmAfterAnotherJvmReclaimsUnknownIntent() throws Exception {
        child("ocr-intent-only");
        Map<String, Object> old = jdbc.queryForMap(
                "SELECT fence,attempts FROM ma_index_job WHERE event_id=?", event);
        expireLease();
        child("ocr-resume-unknown");
        assertThat(jdbc.queryForObject("SELECT fence FROM ma_index_job WHERE event_id=?",
                Long.class, event)).isGreaterThan(((Number) old.get("fence")).longValue());
        awaitChild(startChild("ocr-old-confirm", ((Number) old.get("fence")).longValue(),
                ((Number) old.get("attempts")).intValue()));
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id=?",
                Integer.class, event)).isZero();
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage WHERE event_id=?",
                String.class, event)).isEqualTo("UNKNOWN");
    }

    @Test
    void withdrawalRejectsLateConfirmationFromPreviousJvm() throws Exception {
        child("ocr-intent-only");
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage WHERE event_id=?",
                String.class, event)).isEqualTo("CALL_INTENT");
        Map<String, Object> old = jdbc.queryForMap(
                "SELECT fence,attempts FROM ma_index_job WHERE event_id=?", event);
        long oldFence = ((Number) old.get("fence")).longValue();
        int oldAttempts = ((Number) old.get("attempts")).intValue();
        DeletionJournal journal = DeletionJournal.initialize(logs.resolve("deletion-journal"));
        var intent = journal.append(account, source, Instant.now());
        ChildJvm late = null;
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT account_id FROM app_user WHERE account_id=? FOR UPDATE")) {
                    lock.setLong(1, account);
                    try (ResultSet rows = lock.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }
                try (PreparedStatement deletion = connection.prepareStatement(
                        "SELECT ma_apply_source_deletion(?,?,?,?,?)")) {
                    deletion.setString(1, source);
                    deletion.setLong(2, account);
                    deletion.setTimestamp(3, Timestamp.from(intent.deletedAt()));
                    deletion.setLong(4, intent.sequence());
                    deletion.setString(5, intent.hash());
                    try (ResultSet rows = deletion.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getBoolean(1)).isTrue();
                    }
                }
                late = startChild("ocr-late-confirm", oldFence, oldAttempts);
                awaitAccountLockWait(late);
                connection.commit();
            } catch (Exception failure) {
                if (late != null) {
                    try {
                        stopChild(late, "ABORTED");
                    } catch (Exception cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                try {
                    connection.rollback();
                } catch (Exception rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
        awaitChild(late);
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT input_sha256 IS NULL FROM ma_index_stage WHERE event_id=?",
                Boolean.class, event)).isTrue();
        assertThat(jdbc.queryForObject("SELECT output_json IS NULL FROM ma_index_stage WHERE event_id=?",
                Boolean.class, event)).isTrue();
        assertThat(jdbc.queryForObject("SELECT output_sha256 IS NULL FROM ma_index_stage WHERE event_id=?",
                Boolean.class, event)).isTrue();
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage WHERE event_id=?",
                String.class, event)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id=?",
                Integer.class, event)).isZero();
        assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id=?",
                Boolean.class, event)).isTrue();
    }

    private void expireLease() {
        jdbc.update("UPDATE ma_index_job SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' "
                + "WHERE event_id=?", event);
    }

    private void child(String action) throws Exception {
        ChildJvm started = startChild(action, -1, -1);
        try {
            awaitChild(started);
        } finally {
            if (started.process().isAlive()) {
                stopChild(started, "FINALLY_TERMINATED");
            }
        }
    }

    private ChildJvm startChild(String action, long fence, int attempts) throws IOException {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path output = logs.resolve(action + "-" + UUID.randomUUID() + ".log");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Xmx256m",
                "-XX:ActiveProcessorCount=4", "-cp", classpath,
                IndexStageFaultChildMain.class.getName(), action);
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("FAULT_DB_URL", POSTGRES.getJdbcUrl());
        environment.put("FAULT_DB_USER", POSTGRES.getUsername());
        environment.put("FAULT_DB_PASSWORD", POSTGRES.getPassword());
        environment.put("FAULT_ACCOUNT", Long.toString(account));
        environment.put("FAULT_SOURCE", source);
        environment.put("FAULT_EVENT", event);
        environment.put("FAULT_FILE", file);
        environment.put("FAULT_STUB_URL", stubUrl);
        environment.put("FAULT_STUB_BASE", stubUrl.substring(0, stubUrl.length() - "/paid".length()));
        environment.put("FAULT_MARKER_PATH", logs.resolve(action + "-confirmation.marker").toString());
        String appName = "bl_fault_" + UUID.randomUUID().toString().replace("-", "");
        environment.put("FAULT_APP_NAME", appName);
        if (fence >= 0) {
            environment.put("FAULT_FENCE", Long.toString(fence));
            environment.put("FAULT_ATTEMPTS", Integer.toString(attempts));
        }
        Process process = builder.start();
        ChildJvm child = new ChildJvm(process, output,
                logs.resolve(action + "-" + process.pid() + ".meta"), action,
                appName, Instant.now(), System.nanoTime());
        children.add(child);
        try {
            recordChildState(child, "RUNNING");
        } catch (IOException failure) {
            try {
                stopChild(child, "METADATA_WRITE_FAILED");
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return child;
    }

    private void awaitChild(ChildJvm child) throws Exception {
        Process process = child.process();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            // Record diagnostics before terminating the blocked child.
            String details = "Child JVM timed out: " + child.action()
                    + " pid=" + process.pid() + " elapsedMs="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - child.startedNanos())
                    + " logTail=" + logTail(child.output());
            try {
                Files.writeString(logs.resolve(child.action() + "-timeout.txt"), details);
            } finally {
                stopChild(child, "TIMED_OUT");
            }
            throw new AssertionError(details);
        }
        recordChildState(child, "EXITED");
        int expectedExit = child.action().endsWith("intent-only") ? 72
                : child.action().endsWith("-crash") ? 71
                : child.action().endsWith("-confirm")
                && !child.action().contains("old-") && !child.action().contains("late-") ? 71 : 0;
        assertThat(process.exitValue()).as(child.action() + ": " + logTail(child.output()))
                .isEqualTo(expectedExit);
    }

    private void awaitAccountLockWait(ChildJvm child) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE application_name=? AND wait_event_type='Lock'", Integer.class,
                    child.appName());
            if (waiting != null && waiting > 0) {
                return;
            }
            if (!child.process().isAlive()) {
                throw new AssertionError("Late child exited before lock wait: "
                        + child.process().exitValue() + " " + logTail(child.output()));
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No PostgreSQL lock wait from child pid=" + child.process().pid()
                + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - child.startedNanos()) + " logTail=" + logTail(child.output()));
    }

    private static String logTail(Path output) throws IOException {
        String log = Files.readString(output);
        return log.substring(Math.max(0, log.length() - 4000));
    }

    private static void recordChildState(ChildJvm child, String state) throws IOException {
        Process process = child.process();
        String exitCode = process.isAlive() ? "PENDING" : Integer.toString(process.exitValue());
        String metadata = "pid=" + process.pid() + "\naction=" + child.action()
                + "\napplicationName=" + child.appName()
                + "\nstartedAt=" + child.startedAt()
                + "\nendedAt=" + (process.isAlive() ? "PENDING" : Instant.now())
                + "\nexitCode=" + exitCode
                + "\nelapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - child.startedNanos())
                + "\nstate=" + state + "\n";
        Files.writeString(child.metadata(), metadata);
    }

    private static void stopChild(ChildJvm child, String state) throws Exception {
        Process process = child.process();
        Exception failure = null;
        if (process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = interrupted;
            }
        }
        if (process.isAlive()) {
            IOException unreaped = new IOException("Child JVM remains alive: pid=" + process.pid()
                    + " action=" + child.action() + " applicationName=" + child.appName());
            if (failure == null) {
                failure = unreaped;
            } else {
                failure.addSuppressed(unreaped);
            }
        }
        try {
            recordChildState(child, process.isAlive() ? "REAP_FAILED" : state);
        } catch (IOException metadata) {
            if (failure == null) {
                failure = metadata;
            } else {
                failure.addSuppressed(metadata);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record ChildJvm(Process process, Path output, Path metadata, String action,
            String appName, Instant startedAt, long startedNanos) {
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}

/** Separate Java process; recreates the real MyBatis index store from persisted PostgreSQL state. */
final class IndexStageFaultChildMain {
    @Configuration(proxyBeanMethods = false)
    @MapperScan("com.betterlearning.server.retrieval.mapper")
    @EnableTransactionManagement
    static class MapperConfiguration {
    }

    private IndexStageFaultChildMain() {
    }

    public static void main(String[] args) throws Exception {
        String action = args[0];
        String url = required("FAULT_DB_URL");
        url += (url.contains("?") ? "&" : "?") + "ApplicationName=" + required("FAULT_APP_NAME");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                required("FAULT_DB_USER"), required("FAULT_DB_PASSWORD"));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            JsonMapper json = JsonMapper.builder().build();
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(PlatformTransactionManager.class, () -> {
                return new DataSourceTransactionManager(dataSource);
            });
            context.registerBean(JsonMapper.class, () -> json);
            context.registerBean(SourceAnchorValidator.class, () -> mock(SourceAnchorValidator.class));
            context.registerBean(SourceDeletionService.class, () -> mock(SourceDeletionService.class));
            context.register(MybatisConfiguration.class, LearningMapperConfiguration.class,
                    MapperConfiguration.class, MybatisPlusAutoConfiguration.class,
                    SourceStoreImpl.class, MybatisRetrievalIndexStore.class);
            context.refresh();
            RetrievalIndexStore index = context.getBean(RetrievalIndexStore.class);
            SourceStore sources = context.getBean(SourceStore.class);
            if (action.startsWith("structured-")) {
                Job job = index.claim().orElseThrow();
                processStructured(index, sources, job, action, json);
                return;
            }
            boolean embedding = action.startsWith("embed-");
            StageKey key = stageKey(embedding);
            StageResult expected = stageResult(embedding);
            if (action.endsWith("late-confirm") || action.endsWith("old-confirm")) {
                Job stale = savedJob();
                require(!index.confirmIndexStage(stale, key, expected),
                        "old or withdrawn confirmation accepted");
                if (action.endsWith("late-confirm")) {
                    index.unknownIndexStage(stale, key);
                }
                return;
            }
            if (action.endsWith("resume-unknown")) {
                require(index.claim().isEmpty(), "unknown intent was claimed again");
                return;
            }
            if ("claim-empty".equals(action)) {
                require(index.claim().isEmpty(), "completed source was claimed again");
                return;
            }
            Job job = index.claim().orElseThrow();
            require(index.beginIndexProtocol(job, "HANDWRITING_METHODS_V1"), "protocol not pinned");
            if ("publish-confirmed".equals(action)) {
                publishConfirmed(index, sources, job);
                return;
            }
            RetrievalIndexStore.StageDecision decision = index.beginIndexStage(job, key);
            if (action.endsWith("resume-confirmed")) {
                require(!decision.shouldCall() && expected.equals(decision.confirmed()),
                        "confirmed result was not reused");
                return;
            }
            require(decision.shouldCall(), "new stage did not grant single call");
            String payload = paidCall(expected.outputJson());
            require(expected.outputJson().equals(payload), "loopback stub returned unexpected payload");
            if (action.endsWith("-confirm")) {
                StageResult actual = new StageResult(payload, IndexStagePayloads.sha256(payload),
                        1, embedding ? 7L : null);
                require(index.confirmIndexStage(job, key, actual), "confirmation failed");
                // Deliberately skip shutdown hooks after the committed confirmation.
                Runtime.getRuntime().halt(71);
            } else {
                require(action.endsWith("intent-only"), "unknown child action");
                // Deliberately crash after a paid response and before result confirmation.
                Runtime.getRuntime().halt(72);
            }
        }
    }

    private static Job savedJob() {
        return new Job(required("FAULT_EVENT"), Long.parseLong(required("FAULT_ACCOUNT")),
                required("FAULT_SOURCE"), 1, "SOURCE_VERSION_CREATED",
                Long.parseLong(required("FAULT_FENCE")), 1,
                Integer.parseInt(required("FAULT_ATTEMPTS")));
    }

    private static void processStructured(RetrievalIndexStore index, SourceStore sources,
            Job job, String action, JsonMapper json) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        require(ImageIO.write(image, "png", png), "synthetic PNG encoder unavailable");
        byte[] pixels = png.toByteArray();
        PreparedStep step = new PreparedStep(0,
                new ModelImage("image/png", pixels, "synthetic-step"), 1, 1,
                IndexStagePayloads.sha256(pixels), "{}");
        HandwritingSourceReader reader = mock(HandwritingSourceReader.class);
        when(reader.prepare(anyString())).thenReturn(List.of(step));
        AnchorDraft draft = new AnchorDraft("HANDWRITING_STEP", 0, "synthetic step",
                IndexStagePayloads.sha256("synthetic step"), "{}");
        when(reader.anchorDraft(step, "synthetic step")).thenReturn(draft);
        HandwritingIndexOcr ocr = new HandwritingIndexOcr() {
            @Override
            public String model() {
                return "unit-model";
            }

            @Override
            public HandwritingIndexOcr.Result recognize(ModelImage input, int ordinal,
                    int width, int height) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(
                            URI.create(required("FAULT_STUB_BASE") + "/ocr"))
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(input.bytes())).build();
                    String response = HttpClient.newHttpClient().send(request,
                            HttpResponse.BodyHandlers.ofString()).body();
                    return HandwritingOcrValidator.parse(response, ordinal, width, height);
                } catch (IOException | InterruptedException failure) {
                    throw new IllegalStateException("Loopback OCR unavailable", failure);
                }
            }
        };
        EmbeddingProperties embeddings = new EmbeddingProperties();
        embeddings.setEnabled(true);
        embeddings.setBaseUrl(required("FAULT_STUB_BASE"));
        embeddings.setApiKey("synthetic-test-key");
        embeddings.setModel("unit-model");
        embeddings.setDimensions(2);
        embeddings.setBatchSize(1);
        embeddings.setTimeoutSeconds(5);
        HandwritingMethodsProperties methods = new HandwritingMethodsProperties();
        methods.setEnabled(true);
        RetrievalIndexStore guarded = (RetrievalIndexStore) Proxy.newProxyInstance(
                RetrievalIndexStore.class.getClassLoader(), new Class<?>[] {RetrievalIndexStore.class},
                (proxy, method, arguments) -> {
                    Object result;
                    try {
                        result = method.invoke(index, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if ("confirmIndexStage".equals(method.getName()) && Boolean.TRUE.equals(result)) {
                        StageKey key = (StageKey) arguments[1];
                        boolean crash = "structured-ocr-crash".equals(action)
                                && "OCR_STEP".equals(key.phase())
                                || "structured-embed-crash".equals(action)
                                && "EMBED_BATCH".equals(key.phase());
                        if (crash) {
                            Files.writeString(Path.of(required("FAULT_MARKER_PATH")),
                                    "phase=" + key.phase() + "\nfence=" + job.fence() + "\n");
                            Runtime.getRuntime().halt(71);
                        }
                    }
                    return result;
                });
        StructuredHandwritingIndexer structured = new StructuredHandwritingIndexer(guarded,
                new HttpEmbeddingClient(embeddings), embeddings, methods, reader, ocr, json);
        structured.process(job, sources.get(job.accountId(), job.sourceId(), job.version()));
    }

    private static void publishConfirmed(RetrievalIndexStore index, SourceStore sources, Job job) {
        RetrievalIndexStore.StageDecision ocr = index.beginIndexStage(job, stageKey(false));
        require(!ocr.shouldCall() && stageResult(false).equals(ocr.confirmed()),
                "published OCR stage was not reused");
        RetrievalIndexStore.StageDecision embedding = index.beginIndexStage(job, stageKey(true));
        require(!embedding.shouldCall() && stageResult(true).equals(embedding.confirmed()),
                "published embedding stage was not reused");
        HandwritingIndexOcr.Result recognized = HandwritingOcrValidator.parse(
                ocr.confirmed().outputJson(), 0, 1, 1);
        Anchor anchor = embeddingAnchor();
        String metadata = new HandwritingMethodMetadata(1, job.accountId(), job.sourceId(),
                job.version(), job.indexRevision(), anchor.anchorId(), anchor.contentSha256(),
                "b".repeat(64), 1, 1, "unit-model", recognized).encode();
        SourceContent content = sources.get(job.accountId(), job.sourceId(), job.version());
        Chunk chunk = new Chunk(anchor, content.source().originId(), anchor.text(),
                Set.of(), Set.of(), new double[] {1, 0}, metadata);
        require(index.complete(job, List.of(chunk), "unit-model", 2),
                "single ready version publication failed");
    }

    private static StageKey stageKey(boolean embedding) {
        if (embedding) {
            return new StageKey("EMBED_BATCH", 0, "a".repeat(64), required("FAULT_FILE"),
                    IndexStagePayloads.inputDigest(List.of(embeddingAnchor())), "unit-model", 2,
                    IndexStagePayloads.EMBEDDING_SCHEMA);
        }
        return new StageKey("OCR_STEP", 0, "a".repeat(64), required("FAULT_FILE"),
                "b".repeat(64), "unit-model", 0, "handwriting-ocr-v1:math-methods-v1");
    }

    private static StageResult stageResult(boolean embedding) {
        String output = embedding ? IndexStagePayloads.encodeEmbedding(List.of(embeddingAnchor()),
                List.of(new double[] {1, 0}), 2) : ocrOutput();
        return new StageResult(output, IndexStagePayloads.sha256(output), 1,
                embedding ? 7L : null);
    }

    static String ocrOutput() {
        HandwritingIndexOcr.Result result = new HandwritingIndexOcr.Result(
                HandwritingIndexOcr.SCHEMA_VERSION, HandwritingMethodTaxonomy.VERSION,
                0, "synthetic step", "CLEAR", List.of());
        HandwritingOcrValidator.validate(result, 0, 1, 1);
        return HandwritingOcrValidator.encode(result);
    }

    private static Anchor embeddingAnchor() {
        String source = required("FAULT_SOURCE");
        String anchorId = UUID.nameUUIDFromBytes(
                ("stage-anchor:" + source).getBytes(StandardCharsets.UTF_8)).toString();
        return new Anchor(anchorId, source, 1, "HANDWRITING_STEP", 0,
                IndexStagePayloads.sha256("synthetic step"), "{}", "synthetic step");
    }

    private static String paidCall(String requestBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(required("FAULT_STUB_URL")))
                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing fault-test input: " + key);
        }
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
