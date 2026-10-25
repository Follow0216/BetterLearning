package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.betterlearning.server.learning.service.impl.FileRetentionService;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.retention.SourceRetentionService;
import com.betterlearning.server.multiagent.MultiAgentModels.PublishSource;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class FileRetentionIntegrationTests {
    private static final Path FILE_ROOT = temporaryRoot();
    private static final Path JOURNAL_ROOT = temporaryRoot();
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-file-retention-test-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();
    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "retention", "retention");
        IntegrationProperties.registerRateLimits(registry);
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
        registry.add("app.files.root", FILE_ROOT::toString);
        registry.add("app.agent-retention.delete-log-root", JOURNAL_ROOT::toString);
        registry.add("app.agent-retention.initialize-journal", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private FileRetentionService retention;
    @Autowired
    private AnswerFileStore files;
    @Autowired
    private SourceRetentionService sourceRetention;
    @Autowired
    private SourceStore sources;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, redis);
    }

    @AfterAll
    static void removeOwnedFiles() throws IOException {
        try (var paths = Files.walk(FILE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        try (var paths = Files.walk(JOURNAL_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void activeVersionHoldsFileAndDeletedSourceCanBeCollectedWithTombstone() throws IOException {
        String fileRef = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        Long account = jdbc.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES ('gc_user','hash') RETURNING account_id", Long.class);
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,current_version) "
                + "VALUES (?,?,'NOTE','note-1','笔记',1)", sourceId, account);
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,file_ref) "
                + "VALUES (?,1,?,?)", sourceId, "a".repeat(64), fileRef);
        Path object = FILE_ROOT.resolve("objects").resolve(fileRef);
        Files.createDirectory(object);
        Files.writeString(object.resolve("manifest.json"), "test");

        assertThat(retention.collect(fileRef)).isEqualTo(FileRetentionService.Outcome.ACTIVE_VERSION);
        assertThat(object).exists();
        jdbc.update("UPDATE ma_source SET status='DELETED' WHERE source_id=?", sourceId);
        assertThat(retention.collect(fileRef)).isEqualTo(FileRetentionService.Outcome.UNPURGED_VERSION);
        jdbc.update("UPDATE ma_source_version SET purged_at=CURRENT_TIMESTAMP WHERE source_id=?", sourceId);
        assertThat(retention.collect(fileRef)).isEqualTo(FileRetentionService.Outcome.DELETED);
        assertThat(object).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT physical_deleted_at IS NOT NULL FROM ma_file_gc "
                + "WHERE file_ref=?", Boolean.class, fileRef)).isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE ma_source_version SET file_ref=? "
                + "WHERE source_id=? AND version=1", fileRef, sourceId)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void unknownCandidateRemainsHeld() {
        String fileRef = UUID.randomUUID().toString();
        assertThat(retention.requestCandidateCleanup(fileRef)).isEqualTo(FileRetentionService.Outcome.UNKNOWN);
        assertThat(retention.collect(fileRef)).isEqualTo(FileRetentionService.Outcome.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT hold_reason FROM ma_file_gc WHERE file_ref=?",
                String.class, fileRef)).isEqualTo("UNKNOWN");
        assertThat(retention.groupedCounts()).containsKey("UNKNOWN");
    }

    @Test
    void currentAnswerV1TurnAndSnapshotEachHoldTheirReference() {
        Long account = jdbc.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES ('gc_refs','hash') RETURNING account_id", Long.class);
        Long question = jdbc.queryForObject("INSERT INTO question(account_id,request_id,subject,"
                + "body_markdown,summary) VALUES (?,?,'OTHER','题目','题目') RETURNING question_id",
                Long.class, account, UUID.randomUUID().toString());
        String current = deletedSource(account, "current");
        jdbc.update("INSERT INTO question_answer(account_id,question_id,file_ref,save_request_id,request_hash) "
                + "VALUES (?,?,?,?,?)", account, question, current, UUID.randomUUID().toString(), "a".repeat(64));
        assertThat(retention.collect(current)).isEqualTo(FileRetentionService.Outcome.CURRENT);

        String v1 = deletedSource(account, "v1");
        jdbc.update("INSERT INTO tutor_turn(turn_id,account_id,question_id,request_id,prompt,status,file_ref,request_hash) "
                + "VALUES (?,?,?,?,'旧辅导','GENERATING',?,?)", UUID.randomUUID().toString(), account,
                question, UUID.randomUUID().toString(), v1, "a".repeat(64));
        assertThat(retention.collect(v1)).isEqualTo(FileRetentionService.Outcome.V1);

        String snapshot = deletedSource(account, "snapshot");
        String sourceId = jdbc.queryForObject("SELECT source_id FROM ma_source_version WHERE file_ref=?",
                String.class, snapshot);
        jdbc.update("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,"
                + "snapshot_file_ref,snapshot_source_id,snapshot_version,status,input_deadline_at) "
                + "VALUES (?,?,?,'提示','HINT','NEW_CAPTURE',?,?,1,'WAITING_INPUT',CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                UUID.randomUUID().toString(), account, question, snapshot, sourceId);
        assertThat(retention.collect(snapshot)).isEqualTo(FileRetentionService.Outcome.SNAPSHOT);
        assertThat(retention.groupedCounts()).containsKeys("CURRENT", "V1", "SNAPSHOT");
        assertThat(retention.knownFileAudit(10).missing()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void referenceTransactionWinsLedgerLockAndCollectorObservesIt() throws Exception {
        Long account = account("gc_race_ref");
        Long question = question(account);
        String fileRef = deletedSource(account, "race-ref");
        CountDownLatch referenceInserted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("INSERT INTO question_answer(account_id,question_id,file_ref,save_request_id,request_hash) "
                        + "VALUES (?,?,?,?,?)", account, question, fileRef, UUID.randomUUID().toString(),
                        "a".repeat(64));
                referenceInserted.countDown();
                try {
                    if (!allowCommit.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("reference transaction timeout");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(referenceInserted.await(5, TimeUnit.SECONDS)).isTrue();
            var collector = executor.submit(() -> retention.collect(fileRef));
            assertThat(collector.isDone()).isFalse();
            allowCommit.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertThat(collector.get(5, TimeUnit.SECONDS)).isEqualTo(FileRetentionService.Outcome.CURRENT);
            assertThat(retention.pendingFileRefs(10)).contains(fileRef);
            jdbc.update("DELETE FROM question_answer WHERE file_ref=?", fileRef);
            assertThat(retention.collect(fileRef)).isEqualTo(FileRetentionService.Outcome.DELETED);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void committedTombstoneRejectsNewReferenceTransaction() {
        Long account = account("gc_race_gc");
        Long question = question(account);
        String fileRef = deletedSource(account, "race-gc");
        assertThat(retention.collect(fileRef)).isEqualTo(FileRetentionService.Outcome.DELETED);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbc.update("INSERT INTO question_answer(account_id,question_id,file_ref,save_request_id,request_hash) "
                        + "VALUES (?,?,?,?,?)", account, question, fileRef, UUID.randomUUID().toString(),
                        "a".repeat(64)))).isInstanceOf(RuntimeException.class);
        assertThat(retention.status(fileRef)).isEqualTo(FileRetentionService.Outcome.DELETED);
    }

    @Test
    void terminalRequestKeepsSnapshotMetadataAfterWithdrawnSourceFileCollection() {
        Long account = account("gc_terminal_snapshot");
        Long question = question(account);
        String fileRef = deletedSource(account, "terminal-snapshot");
        String sourceId = jdbc.queryForObject("SELECT source_id FROM ma_source_version WHERE file_ref=?",
                String.class, fileRef);
        String requestId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,"
                + "snapshot_file_ref,snapshot_source_id,snapshot_version,status,input_deadline_at) "
                + "VALUES (?,?,?,'提示','HINT','NEW_CAPTURE',?,?,1,'FAILED',CURRENT_TIMESTAMP)",
                requestId, account, question, fileRef, sourceId);
        assertThat(retention.collect(fileRef)).isEqualTo(FileRetentionService.Outcome.DELETED);
        assertThat(jdbc.queryForObject("SELECT snapshot_file_ref FROM ma_request WHERE request_id=?",
                String.class, requestId)).isEqualTo(fileRef);
    }

    @Test
    void publishedSnapshotWithdrawSweepDeletesFileButKeepsTerminalRequest() throws IOException {
        Long account = account("gc_full_snapshot");
        Long question = question(account);
        String fileRef = UUID.randomUUID().toString();
        Path object = createObject(fileRef);
        String requestId = UUID.randomUUID().toString();
        var published = sources.publish(account, new PublishSource(SourceType.TUTOR_SNAPSHOT,
                requestId, "冻结作答", null, "a".repeat(64), fileRef, null, 0L,
                UUID.randomUUID().toString()));
        jdbc.update("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,"
                + "snapshot_file_ref,snapshot_source_id,snapshot_version,status,input_deadline_at) "
                + "VALUES (?,?,?,'提示','HINT','NEW_CAPTURE',?,?,1,'FAILED',CURRENT_TIMESTAMP)",
                requestId, account, question, fileRef, published.source().sourceId());
        sources.withdraw(account, published.source().sourceId());
        sourceRetention.sweep(100);
        assertThat(object).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT snapshot_file_ref FROM ma_request WHERE request_id=?",
                String.class, requestId)).isEqualTo(fileRef);
        assertThat(jdbc.queryForObject("SELECT purged_at IS NOT NULL FROM ma_source_version "
                + "WHERE source_id=? AND version=1", Boolean.class, published.source().sourceId())).isTrue();
        assertThat(sourceRetention.evidence().unclearedFiles()).isZero();
    }

    @Test
    void publishedSavedAnswerWithdrawSweepKeepsSharedCurrentFile() throws IOException {
        Long account = account("gc_full_current");
        Long question = question(account);
        String fileRef = UUID.randomUUID().toString();
        Path object = createObject(fileRef);
        var published = sources.publish(account, new PublishSource(SourceType.SAVED_ANSWER,
                question.toString(), "当前作答", null, "a".repeat(64), fileRef, "DRAFT", 0L,
                UUID.randomUUID().toString()));
        jdbc.update("INSERT INTO question_answer(account_id,question_id,file_ref,save_request_id,request_hash) "
                + "VALUES (?,?,?,?,?)", account, question, fileRef, UUID.randomUUID().toString(), "a".repeat(64));
        sources.withdraw(account, published.source().sourceId());
        sourceRetention.sweep(100);
        assertThat(object).exists();
        assertThat(retention.status(fileRef)).isEqualTo(FileRetentionService.Outcome.CURRENT);
        assertThat(sourceRetention.evidence().unclearedFiles()).isGreaterThan(0);
    }

    @Test
    void outerTransactionCannotCollectOrPhysicallyDeleteBeforeCommit() throws IOException {
        Long account = account("gc_outer_tx");
        String fileRef = deletedSource(account, "outer-tx");
        Path object = createObject(fileRef);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> retention.collect(fileRef)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("外层事务");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE ma_file_gc SET state='DELETED' WHERE file_ref=?", fileRef);
            files.deletePublished(fileRef);
        }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("数据库事务提交后");
        assertThat(object).exists();
        assertThat(jdbc.queryForObject("SELECT state FROM ma_file_gc WHERE file_ref=?",
                String.class, fileRef)).isEqualTo("TRACKED");
    }

    @Test
    void persistentHoldsDoNotStarveOtherBoundedReconciliationCandidates() {
        long account = account("gc_rotation");
        long question = question(account);
        String first = deletedSource(account, "old-hold");
        String second = deletedSource(account, "next-candidate");
        jdbc.update("INSERT INTO question_answer(account_id,question_id,file_ref,save_request_id,request_hash) "
                + "VALUES (?,?,?,?,?)", account, question, first, UUID.randomUUID().toString(), "a".repeat(64));
        jdbc.update("UPDATE ma_file_gc SET updated_at=CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE file_ref=?", first);
        jdbc.update("UPDATE ma_file_gc SET updated_at=CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE file_ref=?", second);
        assertThat(retention.pendingFileRefs(1)).containsExactly(first);
        assertThat(retention.collect(first)).isEqualTo(FileRetentionService.Outcome.CURRENT);
        assertThat(retention.pendingFileRefs(1)).containsExactly(second);
    }

    private Path createObject(String fileRef) throws IOException {
        Path object = FILE_ROOT.resolve("objects").resolve(fileRef);
        Files.createDirectory(object);
        Files.writeString(object.resolve("manifest.json"), "test");
        return object;
    }

    private Long account(String username) {
        return jdbc.queryForObject("INSERT INTO app_user(username,password_hash) VALUES (?,'hash') "
                + "RETURNING account_id", Long.class, username);
    }

    private Long question(long account) {
        return jdbc.queryForObject("INSERT INTO question(account_id,request_id,subject,body_markdown,summary) "
                + "VALUES (?,?,'OTHER','题目','题目') RETURNING question_id", Long.class,
                account, UUID.randomUUID().toString());
    }

    private String deletedSource(long account, String origin) {
        String fileRef = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?,'NOTE',?,'笔记','DELETED',1)", sourceId, account, origin);
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,file_ref) "
                + "VALUES (?,1,?,?)", sourceId, "a".repeat(64), fileRef);
        jdbc.update("UPDATE ma_source_version SET purged_at=CURRENT_TIMESTAMP WHERE source_id=?", sourceId);
        return fileRef;
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("betterlearning-retention-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
