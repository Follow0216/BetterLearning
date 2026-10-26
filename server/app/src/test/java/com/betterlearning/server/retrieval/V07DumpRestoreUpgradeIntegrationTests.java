package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.betterlearning.server.agent.retrieval.handwriting.HandwritingMethodMetadata;
import com.betterlearning.server.agent.retrieval.ranking.HandwritingMethodTaxonomy;
import com.betterlearning.server.agent.service.HandwritingIndexOcr;
import com.betterlearning.server.learning.retention.DeletionJournal;
import com.betterlearning.server.support.IntegrationContainers;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import tools.jackson.databind.json.JsonMapper;

/** V07 database dump and restore into a second, new PostgreSQL container. */
@Testcontainers
@Tag("integration")
class V07DumpRestoreUpgradeIntegrationTests {
    private static final String SOURCE_SHA = "a".repeat(64);
    private static final String OCR_OUTPUT = "{\"transcript\":\"private legacy OCR\"}";

    @org.testcontainers.junit.jupiter.Container
    static final PostgreSQLContainer SOURCE = IntegrationContainers.postgres();

    @org.testcontainers.junit.jupiter.Container
    static final PostgreSQLContainer TARGET = IntegrationContainers.postgres();

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void syntheticV07DumpRestoresIntoNewPostgresThenUpgradesV08V09V10() throws Exception {
        assertThat(SOURCE.getContainerId()).isNotBlank();
        assertThat(TARGET.getContainerId()).isNotBlank().isNotEqualTo(SOURCE.getContainerId());
        assertThat(new JdbcTemplate(dataSource(TARGET)).queryForObject(
                "SELECT to_regclass('flyway_schema_history') IS NULL", Boolean.class)).isTrue();
        JdbcTemplate old = jdbc(SOURCE);
        migrate(SOURCE, "2026102507");
        long account = old.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES (?,'test-hash') RETURNING account_id", Long.class, username());
        StageSeed erased = stageSeed(old, account);
        ProjectionSeed live = projectionSeed(old, account);
        StageSeed priorDeletion = stageSeed(old, account);
        Path base = evidenceRoot();
        JsonMapper json = JsonMapper.builder().build();
        DeletionJournal journal = DeletionJournal.initialize(base.resolve("synthetic-journal"));
        var beforeDumpIntent = journal.append(account, priorDeletion.source(),
                Instant.parse("2026-10-25T00:00:00Z"));
        assertThat(old.queryForObject("SELECT ma_apply_source_deletion(?,?,?,?,?)", Boolean.class,
                priorDeletion.source(), account, Timestamp.from(beforeDumpIntent.deletedAt()),
                beforeDumpIntent.sequence(), beforeDumpIntent.hash())).isTrue();
        assertThat(old.queryForObject("SELECT status FROM ma_source WHERE source_id=?",
                String.class, priorDeletion.source())).isEqualTo("DELETED");
        assertThat(old.queryForObject("SELECT status FROM ma_source_version WHERE source_id=?",
                String.class, priorDeletion.source())).isEqualTo("WITHDRAWN");
        assertThat(old.queryForObject("SELECT count(*) FROM ma_source_deletion WHERE source_id=? "
                + "AND journal_sequence=? AND journal_hash=?", Integer.class, priorDeletion.source(),
                beforeDumpIntent.sequence(), beforeDumpIntent.hash())).isEqualTo(1);
        Map<String, Object> beforeDumpRows = preservationSnapshot(old);
        Map<String, Object> beforeDumpSequence = accountSequence(old);
        List<Long> originalAccountIds = old.queryForList("SELECT account_id FROM app_user "
                + "ORDER BY account_id", Long.class);
        long oldMaximumAccountId = old.queryForObject("SELECT MAX(account_id) FROM app_user", Long.class);
        assertThat(old.queryForList("SELECT source_id FROM ma_source ORDER BY source_id", String.class))
                .containsExactlyInAnyOrder(erased.source(), live.source(), priorDeletion.source());
        assertThat(old.queryForList("SELECT anchor_id FROM ma_anchor", String.class))
                .containsExactly(live.anchor());
        assertThat(old.queryForList("SELECT chunk_id FROM ma_retrieval_chunk", String.class))
                .containsExactly(live.chunk());
        Files.writeString(base.resolve("before-dump-preservation.json"),
                json.writeValueAsString(beforeDumpRows) + "\n", StandardCharsets.UTF_8);
        assertThat(old.queryForObject("SELECT to_regclass('ma_index_stage') IS NULL",
                Boolean.class)).isTrue();
        Map<String, Object> oldHistory = history(old, "2026102507");
        assertThat(oldHistory.get("success")).isEqualTo(true);
        assertThat(old.queryForObject("SELECT count(*) FROM ma_source", Integer.class)).isEqualTo(3);
        assertThat(old.queryForObject("SELECT count(*) FROM ma_retrieval_chunk",
                Integer.class)).isEqualTo(1);

        Map<String, Object> containers = Map.of(
                "sourceId", SOURCE.getContainerId(), "targetId", TARGET.getContainerId(),
                "sourceMappedPort", SOURCE.getMappedPort(5432),
                "targetMappedPort", TARGET.getMappedPort(5432),
                "sourceImage", SOURCE.getDockerImageName(),
                "targetImage", TARGET.getDockerImageName(),
                "targetFlywayHistoryAbsentBeforeRestore", true);
        Files.writeString(base.resolve("containers.json"),
                json.writeValueAsString(containers) + "\n", StandardCharsets.UTF_8);
        Path dump = base.resolve("synthetic-v07.dump");
        Container.ExecResult dumped = SOURCE.execInContainer("pg_dump", "-U", SOURCE.getUsername(),
                "-Fc", "--no-owner", "--no-acl", "-f", "/tmp/synthetic-v07.dump",
                SOURCE.getDatabaseName());
        writeProcessEvidence(base, "pg-dump", dumped, json);
        assertThat(dumped.getExitCode()).as(dumped.getStderr()).isZero();
        SOURCE.copyFileFromContainer("/tmp/synthetic-v07.dump", dump.toString());
        String dumpHash = sha(Files.readAllBytes(dump));
        assertThat(Files.size(dump)).isPositive();
        TARGET.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/synthetic-v07.dump");
        Container.ExecResult restored = TARGET.execInContainer("pg_restore", "-U",
                TARGET.getUsername(), "-d", TARGET.getDatabaseName(), "--clean", "--if-exists",
                "--no-owner", "--no-acl", "/tmp/synthetic-v07.dump");
        writeProcessEvidence(base, "pg-restore", restored, json);
        Map<String, Object> processEvidence = Map.of("scope", "two-new-testcontainers-postgres",
                "dumpSha256", dumpHash, "dumpBytes", Files.size(dump),
                "pgDumpExit", dumped.getExitCode(), "pgRestoreExit", restored.getExitCode(),
                "pgDumpStdoutSha256", sha(dumped.getStdout().getBytes(StandardCharsets.UTF_8)),
                "pgDumpStderrSha256", sha(dumped.getStderr().getBytes(StandardCharsets.UTF_8)),
                "pgRestoreStdoutSha256", sha(restored.getStdout().getBytes(StandardCharsets.UTF_8)),
                "pgRestoreStderrSha256", sha(restored.getStderr().getBytes(StandardCharsets.UTF_8)));
        Files.writeString(base.resolve("process-evidence.json"),
                json.writeValueAsString(processEvidence) + "\n", StandardCharsets.UTF_8);
        assertThat(restored.getExitCode()).as(restored.getStderr()).isZero();
        JdbcTemplate fresh = jdbc(TARGET);
        assertThat(history(fresh, "2026102507")).isEqualTo(oldHistory);
        assertThat(fresh.queryForObject("SELECT count(*) FROM ma_source", Integer.class)).isEqualTo(3);
        int restoredV07SourceRows = fresh.queryForObject("SELECT count(*) FROM ma_source", Integer.class);
        Map<String, Object> restoredRows = preservationSnapshot(fresh);
        assertThat(restoredRows).as("all selected V07 identities and fields survive restore")
                .isEqualTo(beforeDumpRows);
        Map<String, Object> restoredSequence = accountSequence(fresh);
        assertThat(restoredSequence).isEqualTo(beforeDumpSequence);
        long nextAccount = fresh.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES (?,'test-hash') RETURNING account_id", Long.class, username());
        assertThat(nextAccount).isGreaterThan(oldMaximumAccountId);
        assertThat(originalAccountIds).doesNotContain(nextAccount);
        assertThat(fresh.queryForObject("SELECT last_value FROM public.app_user_account_id_seq",
                Long.class)).isEqualTo(nextAccount);
        assertThat(fresh.queryForObject("SELECT count(*) FROM ma_source_deletion WHERE source_id=? "
                + "AND journal_sequence=? AND journal_hash=?", Integer.class, priorDeletion.source(),
                beforeDumpIntent.sequence(), beforeDumpIntent.hash())).isEqualTo(1);
        Files.writeString(base.resolve("after-restore-preservation.json"),
                json.writeValueAsString(restoredRows) + "\n", StandardCharsets.UTF_8);
        assertThat(fresh.queryForObject("SELECT count(*) FROM ma_retrieval_chunk",
                Integer.class)).isEqualTo(1);
        assertThat(fresh.queryForObject("SELECT to_regclass('ma_index_stage') IS NULL",
                Boolean.class)).isTrue();
        assertThat(fresh.queryForObject("SELECT count(*) FROM ma_index_job WHERE event_id=?",
                Integer.class, erased.event())).isEqualTo(1);
        assertThat(fresh.queryForObject("SELECT count(*) FROM ma_source WHERE source_id=?",
                Integer.class, live.source())).isEqualTo(1);

        migrate(TARGET, "2026102508");
        assertThat(fresh.queryForObject("SELECT to_regclass('ma_index_stage') IS NOT NULL",
                Boolean.class)).isTrue();
        addV08ConfirmedStage(fresh, account, erased);
        String rawMetadata = bindV08Metadata(fresh, account, live);
        var afterRestoreIntent = journal.append(account, erased.source(),
                Instant.parse("2026-10-25T00:01:00Z"));
        assertThat(afterRestoreIntent.sequence()).isGreaterThan(beforeDumpIntent.sequence());
        assertThat(fresh.queryForObject("SELECT ma_apply_source_deletion(?,?,?,?,?)", Boolean.class,
                erased.source(), account, Timestamp.from(afterRestoreIntent.deletedAt()),
                afterRestoreIntent.sequence(), afterRestoreIntent.hash())).isTrue();
        fresh.queryForObject("SELECT ma_scrub_source_version(?,1)",
                Boolean.class, erased.source());
        fresh.update("UPDATE ma_source_deletion SET database_purged_at=CURRENT_TIMESTAMP "
                + "WHERE source_id=?", erased.source());
        assertThat(fresh.queryForObject("SELECT output_json FROM ma_index_stage WHERE event_id=?",
                String.class, erased.event())).isEqualTo(OCR_OUTPUT);
        assertThat(fresh.queryForObject("SELECT purged_at IS NOT NULL FROM ma_source_version "
                + "WHERE source_id=? AND version=1", Boolean.class, erased.source())).isTrue();

        migrate(TARGET, "2026102509");
        assertThat(fresh.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=? "
                + "AND purged_at IS NOT NULL AND output_json IS NULL AND output_sha256 IS NULL "
                + "AND input_sha256 IS NULL AND call_state='CONFIRMED' AND request_count=1",
                Integer.class, erased.event())).isEqualTo(1);
        assertThat(fresh.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id=?",
                Integer.class, erased.event())).isEqualTo(1);
        assertThat(fresh.queryForObject("SELECT status FROM ma_source WHERE source_id=?",
                String.class, erased.source())).isEqualTo("DELETED");

        migrate(TARGET, "2026102510");
        Map<String, Object> projection = fresh.queryForMap("SELECT inferred_method_tags::text AS inferred,"
                + "method_tags::text AS literal,method_metadata::text AS raw,"
                + "method_projection_hash,md5(method_metadata::text) AS raw_hash "
                + "FROM ma_retrieval_chunk WHERE chunk_id=?", live.chunk());
        assertThat(projection.get("inferred")).isEqualTo("[\"配方法\"]");
        assertThat(projection.get("literal")).isEqualTo("[]");
        assertThat(projection.get("raw")).isEqualTo(rawMetadata);
        assertThat(projection.get("method_projection_hash")).isEqualTo(projection.get("raw_hash"));
        migrate(TARGET, "2026102602");
        assertThat(fresh.queryForObject("SELECT to_regclass('ma_note_context') IS NOT NULL", Boolean.class)).isTrue();
        migrate(TARGET, "2026102603");
        for (String version : List.of("2026102507", "2026102508", "2026102509", "2026102510", "2026102602", "2026102603")) {
            assertThat(history(fresh, version).get("success")).isEqualTo(true);
        }
        assertThat(Flyway.configure().dataSource(dataSource(TARGET))
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("2026102603"))
                .load().migrate().migrationsExecuted).isZero();
        Map<String, Object> evidence = new LinkedHashMap<>(processEvidence);
        evidence.put("containers", containers);
        evidence.put("sourceV07History", oldHistory);
        evidence.put("restoredV07History", history(fresh, "2026102507"));
        evidence.put("targetV08History", history(fresh, "2026102508"));
        evidence.put("targetV09History", history(fresh, "2026102509"));
        evidence.put("targetV10History", history(fresh, "2026102510"));
        evidence.put("targetNoteWorkspaceHistory", history(fresh, "2026102602"));
        evidence.put("targetConversationContextHistory", history(fresh, "2026102603"));
        evidence.put("sourceV07SourceRows", 3);
        evidence.put("beforeDumpRows", beforeDumpRows);
        evidence.put("afterRestoreRowsBeforeUpgrade", restoredRows);
        evidence.put("preservedIdentityFieldsEqual", restoredRows.equals(beforeDumpRows));
        evidence.put("identitySequenceName", "public.app_user_account_id_seq");
        evidence.put("identitySequenceBeforeDump", beforeDumpSequence);
        evidence.put("identitySequenceAfterRestoreBeforeInsert", restoredSequence);
        evidence.put("maximumAccountIdBeforeDump", oldMaximumAccountId);
        evidence.put("generatedAccountIdAfterRestore", nextAccount);
        evidence.put("preDumpDeletionSourceId", priorDeletion.source());
        evidence.put("preDumpDeletionJournalSequence", beforeDumpIntent.sequence());
        evidence.put("preDumpDeletionJournalHash", beforeDumpIntent.hash());
        evidence.put("newDeletionSequenceAfterRestore", afterRestoreIntent.sequence());
        evidence.put("standaloneDeletionJournalFileRestoreVerified", false);
        evidence.put("restoredV07SourceRowsBeforeUpgrade", restoredV07SourceRows);
        evidence.put("retiredStagePayloadRowsRemaining", fresh.queryForObject(
                "SELECT count(*) FROM ma_index_stage WHERE event_id=? AND (input_sha256 IS NOT NULL "
                + "OR output_json IS NOT NULL OR output_sha256 IS NOT NULL)",
                Integer.class, erased.event()));
        evidence.put("confirmedOcrCalls", fresh.queryForObject(
                "SELECT ocr_calls FROM ma_index_job WHERE event_id=?", Integer.class,
                erased.event()));
        evidence.put("liveInferredMethods", projection.get("inferred"));
        evidence.put("liveMethodMetadataPreserved", projection.get("raw").equals(rawMetadata));
        Files.writeString(base.resolve("receipt.json"),
                json.writeValueAsString(evidence) + "\n", StandardCharsets.UTF_8);
        System.out.println("P6_V07_DUMP_RESTORE_EVIDENCE " + base.resolve("receipt.json"));
    }

    private static StageSeed stageSeed(JdbcTemplate jdbc, long account) {
        String source = id();
        String event = id();
        String fileRef = id();
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?,'SAVED_ANSWER',?,'legacy','ACTIVE',1)", source, account, id());
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,"
                + "index_revision,file_ref) VALUES (?,1,?,'READY',1,?)", source, SOURCE_SHA, fileRef);
        jdbc.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,"
                + "aggregate_version,kind,payload_digest,index_revision,status) "
                + "VALUES (?,?,'SOURCE',?,1,'SOURCE_VERSION_CREATED',?,1,'DELIVERED')",
                event, account, source, SOURCE_SHA);
        jdbc.update("INSERT INTO ma_index_job(event_id,status,call_state,fence,ocr_calls) "
                + "VALUES (?,'DONE','NOT_SENT',7,1)", event);
        return new StageSeed(source, event, fileRef);
    }

    private static ProjectionSeed projectionSeed(JdbcTemplate jdbc, long account) {
        String source = id();
        String event = id();
        String anchor = id();
        String chunk = id();
        String text = "$$x^2+2x=(x+1)^2-1$$";
        String digest = sha(text.getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?,'SAVED_ANSWER',?,'live','ACTIVE',1)", source, account, id());
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,"
                + "index_revision,file_ref) VALUES (?,1,?,'READY',1,?)", source, SOURCE_SHA, id());
        jdbc.update("INSERT INTO ma_anchor(anchor_id,source_id,version,kind,ordinal,content_sha256,"
                + "locator_json,text) VALUES (?,?,1,'HANDWRITING_STEP',0,?,'{}',?)",
                anchor, source, digest, text);
        jdbc.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,"
                + "aggregate_version,kind,payload_digest,index_revision,status) "
                + "VALUES (?,?,'SOURCE',?,1,'SOURCE_VERSION_CREATED',?,1,'DELIVERED')",
                event, account, source, SOURCE_SHA);
        jdbc.update("INSERT INTO ma_index_job(event_id,status,call_state) "
                + "VALUES (?,'DONE','NOT_SENT')", event);
        jdbc.update("INSERT INTO ma_retrieval_chunk(chunk_id,account_id,source_id,version,anchor_id,"
                + "text,method_tags,terms,embedding,embedding_model,dimensions,index_revision) "
                + "VALUES (?,?,?,1,?,?,'[]'::jsonb,'[]'::jsonb,CAST('[1,0]' AS public.vector),"
                + "'test-model',2,1)", chunk, account, source, anchor, text);
        return new ProjectionSeed(source, chunk, anchor, text, digest);
    }

    private static void addV08ConfirmedStage(JdbcTemplate jdbc, long account, StageSeed seed) {
        jdbc.update("UPDATE ma_index_job SET index_protocol='HANDWRITING_METHODS_V1' "
                + "WHERE event_id=?", seed.event());
        jdbc.update("INSERT INTO ma_index_stage(event_id,phase,ordinal,account_id,source_id,"
                + "source_version,index_revision,source_sha256,file_ref,input_sha256,model,"
                + "dimensions,schema_version,call_state,intent_fence,output_json,output_sha256,"
                + "request_count,confirmed_at) VALUES (?,'OCR_STEP',0,?,?,1,1,?,?,?,'ocr-model',0,"
                + "'handwriting-ocr-v1:math-methods-v1','CONFIRMED',7,?,?,1,CURRENT_TIMESTAMP)",
                seed.event(), account, seed.source(), SOURCE_SHA, seed.fileRef(),
                "b".repeat(64), OCR_OUTPUT, sha(OCR_OUTPUT.getBytes(StandardCharsets.UTF_8)));
    }

    private static String bindV08Metadata(JdbcTemplate jdbc, long account, ProjectionSeed seed) {
        HandwritingIndexOcr.Result result = new HandwritingIndexOcr.Result(
                HandwritingIndexOcr.SCHEMA_VERSION, HandwritingMethodTaxonomy.VERSION, 0,
                seed.text(), "CLEAR", List.of(new HandwritingIndexOcr.MethodCandidate(
                "配方法", 0, seed.text().length(), new HandwritingIndexOcr.Region(0, 0, 1, 1),
                "CANDIDATE", "VISIBLE_STEPS")));
        String metadata = new HandwritingMethodMetadata(HandwritingMethodMetadata.FORMAT_VERSION,
                account, seed.source(), 1, 1, seed.anchor(), seed.digest(), "b".repeat(64),
                10, 10, "old-structured-ocr", result).encode();
        jdbc.update("UPDATE ma_retrieval_chunk SET method_metadata=CAST(? AS jsonb) WHERE chunk_id=?",
                metadata, seed.chunk());
        return jdbc.queryForObject("SELECT method_metadata::text FROM ma_retrieval_chunk "
                + "WHERE chunk_id=?", String.class, seed.chunk());
    }

    private static Map<String, Object> preservationSnapshot(JdbcTemplate jdbc) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sources", jdbc.queryForList("SELECT source_id,account_id,type,origin_id,title,"
                + "status,current_version,created_at::text,deleted_at::text FROM ma_source ORDER BY source_id"));
        snapshot.put("versions", jdbc.queryForList("SELECT source_id,version,content_sha256,status,"
                + "index_revision,answer_state,file_ref,body_markdown,created_at::text,purged_at::text,"
                + "purge_reason FROM ma_source_version ORDER BY source_id,version"));
        snapshot.put("anchors", jdbc.queryForList("SELECT anchor_id,source_id,version,kind,ordinal,"
                + "content_sha256,locator_json,text FROM ma_anchor ORDER BY anchor_id"));
        snapshot.put("chunks", jdbc.queryForList("SELECT chunk_id,account_id,source_id,version,anchor_id,"
                + "question_id,text,method_tags::text,terms::text,embedding::text,embedding_model,dimensions,"
                + "index_revision FROM ma_retrieval_chunk ORDER BY chunk_id"));
        snapshot.put("deletions", jdbc.queryForList("SELECT source_id,account_id,deleted_at::text,"
                + "journal_sequence,journal_hash,database_purged_at::text,hold_reason,checked_at::text "
                + "FROM ma_source_deletion ORDER BY source_id"));
        snapshot.put("deletionReplay", jdbc.queryForList("SELECT singleton,sequence,hash,replayed_at::text "
                + "FROM ma_deletion_replay ORDER BY singleton"));
        snapshot.put("indexJobs", jdbc.queryForList("SELECT event_id,status,call_state,fence,attempts,"
                + "ocr_calls,embedding_requests,embedding_tokens,usage_uncertain FROM ma_index_job ORDER BY event_id"));
        snapshot.put("outbox", jdbc.queryForList("SELECT event_id,account_id,aggregate_type,aggregate_id,"
                + "aggregate_version,kind,payload_digest,status,index_revision FROM ma_outbox ORDER BY event_id"));
        return snapshot;
    }

    private static Map<String, Object> accountSequence(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT pg_get_serial_sequence('public.app_user','account_id')",
                String.class)).isEqualTo("public.app_user_account_id_seq");
        return jdbc.queryForMap("SELECT last_value,is_called FROM public.app_user_account_id_seq");
    }

    private static Map<String, Object> history(JdbcTemplate jdbc, String version) {
        return jdbc.queryForMap("SELECT version,description,script,type,checksum,success "
                + "FROM flyway_schema_history WHERE version=?", version);
    }

    private static void migrate(PostgreSQLContainer postgres, String version) {
        Flyway.configure().dataSource(dataSource(postgres)).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer postgres) {
        return new JdbcTemplate(dataSource(postgres));
    }

    private static DataSource dataSource(PostgreSQLContainer postgres) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
    }

    private static Path evidenceRoot() throws Exception {
        String configured = System.getenv("APP_TEST_FILES_ROOT");
        Path parent = configured == null ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(configured);
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, "p6-v07-dump-restore-");
    }

    private static void writeProcessEvidence(Path base, String name, Container.ExecResult result,
            JsonMapper json) throws Exception {
        byte[] stdout = result.getStdout().getBytes(StandardCharsets.UTF_8);
        byte[] stderr = result.getStderr().getBytes(StandardCharsets.UTF_8);
        Files.write(base.resolve(name + ".stdout.log"), stdout);
        Files.write(base.resolve(name + ".stderr.log"), stderr);
        Files.writeString(base.resolve(name + "-process.json"), json.writeValueAsString(Map.of(
                "exitCode", result.getExitCode(), "stdoutSha256", sha(stdout),
                "stderrSha256", sha(stderr), "stdoutBytes", stdout.length,
                "stderrBytes", stderr.length)) + "\n", StandardCharsets.UTF_8);
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String username() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private record StageSeed(String source, String event, String fileRef) { }

    private record ProjectionSeed(String source, String chunk, String anchor, String text,
            String digest) { }
}
