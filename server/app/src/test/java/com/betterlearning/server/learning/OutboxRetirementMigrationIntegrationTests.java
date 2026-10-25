package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.support.IntegrationContainers;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 从 V2026102506 的真实关联数据升级，只有已被 SOURCE 事实完整替代的 RESULT 事件可退休。 */
@Testcontainers
@Tag("integration")
class OutboxRetirementMigrationIntegrationTests {
    private static final String MIGRATION = "V2026102507__retire_redundant_result_outbox.sql";
    private static final String REASON = "REDUNDANT_RESULT_PUBLICATION";

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Test
    void retiresOnlyProvablyRedundantResultEventsAndKeepsAuditAndOtherWork() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2026102506")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long owner = account(jdbc, "retirement_owner");
        long other = account(jdbc, "retirement_other");
        long question = question(jdbc, owner);
        List<Candidate> accepted = new ArrayList<>();
        List<Candidate> rejected = new ArrayList<>();

        accepted.add(candidate(jdbc, owner, question, "PENDING"));
        Candidate failedResultWithFailedSource = candidate(jdbc, owner, question, "FAILED");
        jdbc.update("UPDATE ma_outbox SET status = 'FAILED' WHERE event_id = ?",
                failedResultWithFailedSource.sourceEvent());
        accepted.add(failedResultWithFailedSource);
        Candidate orphan = candidate(jdbc, owner, question, "PENDING");
        String missingResultId = id();
        jdbc.update("UPDATE ma_outbox SET aggregate_id = ?, payload_digest = ? WHERE event_id = ?",
                missingResultId, sha(missingResultId + ":1:RESULT_PUBLISHED"), orphan.resultEvent());
        rejected.add(orphan);
        Candidate wrongAccount = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("UPDATE ma_outbox SET account_id = ? WHERE event_id = ?",
                other, wrongAccount.resultEvent());
        rejected.add(wrongAccount);
        Candidate wrongSourceEventAccount = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("UPDATE ma_outbox SET account_id = ? WHERE event_id = ?",
                other, wrongSourceEventAccount.sourceEvent());
        rejected.add(wrongSourceEventAccount);
        Candidate badResultDigest = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("UPDATE ma_outbox SET payload_digest = ? WHERE event_id = ?",
                "f".repeat(64), badResultDigest.resultEvent());
        rejected.add(badResultDigest);
        Candidate unfinishedRun = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("UPDATE ma_run SET status = 'FAILED' WHERE run_id = ?", unfinishedRun.runId());
        jdbc.update("UPDATE ma_request SET status = 'FAILED' WHERE request_id = ?", unfinishedRun.requestId());
        rejected.add(unfinishedRun);
        Candidate wrongMode = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("UPDATE ma_result SET mode = 'FULL_SOLUTION' WHERE result_id = ?", wrongMode.resultId());
        rejected.add(wrongMode);
        Candidate withheld = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("UPDATE ma_result SET status = 'WITHHELD' WHERE result_id = ?", withheld.resultId());
        rejected.add(withheld);
        Candidate noSourceEvent = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("DELETE FROM ma_outbox WHERE event_id = ?", noSourceEvent.sourceEvent());
        rejected.add(noSourceEvent);
        Candidate noOperation = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("DELETE FROM ma_source_operation WHERE account_id = ? AND operation_id = ?",
                owner, "result:" + noOperation.resultId());
        rejected.add(noOperation);
        Candidate badBodyHash = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("UPDATE ma_source_version SET content_sha256 = ? WHERE source_id = ? AND version = 1",
                "f".repeat(64), badBodyHash.sourceId());
        jdbc.update("UPDATE ma_outbox SET payload_digest = ? WHERE event_id = ?",
                "f".repeat(64), badBodyHash.sourceEvent());
        rejected.add(badBodyHash);
        Candidate wrongBody = candidate(jdbc, owner, question, "PENDING");
        String differentBody = "另一份完整但不属于结果的答复";
        String differentDigest = sha(differentBody);
        jdbc.update("UPDATE ma_source_version SET body_markdown = ?, content_sha256 = ? "
                + "WHERE source_id = ? AND version = 1",
                differentBody, differentDigest, wrongBody.sourceId());
        jdbc.update("UPDATE ma_outbox SET payload_digest = ? WHERE event_id = ?",
                differentDigest, wrongBody.sourceEvent());
        rejected.add(wrongBody);
        Candidate hasIndexJob = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("INSERT INTO ma_index_job(event_id) VALUES (?)", hasIndexJob.resultEvent());
        rejected.add(hasIndexJob);
        Candidate deletedSource = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("UPDATE ma_source SET status = 'DELETED' WHERE source_id = ?", deletedSource.sourceId());
        rejected.add(deletedSource);
        Candidate wrongVersion = candidate(jdbc, owner, question, "PENDING");
        jdbc.update("UPDATE ma_source SET current_version = 2 WHERE source_id = ?", wrongVersion.sourceId());
        rejected.add(wrongVersion);
        Candidate delivered = candidate(jdbc, owner, question, "DELIVERED");
        rejected.add(delivered);

        Map<String, OriginalEvent> originals = new LinkedHashMap<>();
        for (Candidate item : accepted) {
            originals.put(item.resultEvent(), original(jdbc, item.resultEvent()));
        }
        for (Candidate item : rejected) {
            originals.put(item.resultEvent(), original(jdbc, item.resultEvent()));
        }
        Candidate sourceToKeep = accepted.get(0);
        String runEvent = id();
        jdbc.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,aggregate_version,"
                + "kind,payload_digest,index_revision,status) VALUES (?,?,'RUN',?,1,'RUN_START',?,0,'PENDING')",
                runEvent, owner, sourceToKeep.runId(), "a".repeat(64));
        OriginalEvent sourceOriginal = original(jdbc, sourceToKeep.sourceEvent());
        OriginalEvent runOriginal = original(jdbc, runEvent);

        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '2026102507' AND success", Integer.class)).isEqualTo(1);
        for (Candidate item : accepted) {
            Map<String, Object> row = jdbc.queryForMap("SELECT status,retired_at,retired_reason,retired_by,"
                    + "retired_from_status FROM ma_outbox WHERE event_id = ?", item.resultEvent());
            assertThat(row.get("status")).isEqualTo("RETIRED");
            assertThat(row.get("retired_at")).isNotNull();
            assertThat(row.get("retired_reason")).isEqualTo(REASON);
            assertThat(row.get("retired_by")).isEqualTo(MIGRATION);
            assertThat(row.get("retired_from_status")).isEqualTo(originals.get(item.resultEvent()).status());
            assertIdentityUnchanged(jdbc, item.resultEvent(), originals.get(item.resultEvent()));
        }
        for (Candidate item : rejected) {
            assertUnchanged(jdbc, item.resultEvent(), originals.get(item.resultEvent()));
        }
        assertUnchanged(jdbc, sourceToKeep.sourceEvent(), sourceOriginal);
        assertUnchanged(jdbc, runEvent, runOriginal);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox WHERE status = 'RETIRED'",
                Integer.class)).isEqualTo(2);

        Map<String, Object> firstAudit = jdbc.queryForMap("SELECT retired_at,retired_reason,retired_by,"
                + "retired_from_status FROM ma_outbox WHERE event_id = ?", accepted.get(0).resultEvent());
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForMap("SELECT retired_at,retired_reason,retired_by,"
                + "retired_from_status FROM ma_outbox WHERE event_id = ?", accepted.get(0).resultEvent()))
                .isEqualTo(firstAudit);
        assertThatThrownBy(() -> jdbc.update("UPDATE ma_outbox SET status = 'RETIRED', "
                + "retired_at = CURRENT_TIMESTAMP, retired_reason = ?, retired_by = ?, "
                + "retired_from_status = 'PENDING' WHERE event_id = ?",
                REASON, MIGRATION, sourceToKeep.sourceEvent()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertUnchanged(jdbc, sourceToKeep.sourceEvent(), sourceOriginal);
    }

    private static Candidate candidate(JdbcTemplate jdbc, long owner, long question, String eventStatus) {
        String request = id();
        String run = id();
        String result = id();
        String source = id();
        String resultEvent = id();
        String sourceEvent = id();
        String body = "已核验答复-" + result.substring(0, 8);
        String bodyDigest = sha(body);
        String resultDigest = sha(result + ":1:RESULT_PUBLISHED");
        jdbc.update("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,"
                + "status,input_deadline_at) VALUES (?,?,?,'解释下一步','HINT','NEW_CAPTURE',"
                + "'SUCCEEDED',CURRENT_TIMESTAMP + INTERVAL '5 minutes')", request, owner, question);
        jdbc.update("INSERT INTO ma_run(run_id,request_id,account_id,run_no,status,queue_deadline_at,"
                + "run_deadline_at) VALUES (?,?,?,1,'SUCCEEDED',CURRENT_TIMESTAMP + INTERVAL '2 minutes',"
                + "CURRENT_TIMESTAMP + INTERVAL '10 minutes')", run, request, owner);
        jdbc.update("UPDATE ma_request SET current_run_id = ? WHERE account_id = ? AND request_id = ?",
                run, owner, request);
        jdbc.update("INSERT INTO ma_result(result_id,run_id,mode,status,body_markdown) "
                + "VALUES (?,?,'HINT','VERIFIED',?)", result, run, body);
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?,'TUTOR_REPLY',?,'已发布辅导','ACTIVE',1)", source, owner, result);
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,"
                + "body_markdown) VALUES (?,1,?,'INDEX_PENDING',?)", source, bodyDigest, body);
        jdbc.update("INSERT INTO ma_source_operation(account_id,operation_id,payload_fingerprint,source_id,version) "
                + "VALUES (?,?,?,?,1)", owner, "result:" + result, bodyDigest, source);
        jdbc.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,aggregate_version,"
                + "kind,payload_digest,index_revision,status) VALUES (?,?,'SOURCE',?,1,'SOURCE_VERSION_CREATED',"
                + "?,1,'PENDING')", sourceEvent, owner, source, bodyDigest);
        jdbc.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,aggregate_version,"
                + "kind,payload_digest,index_revision,status,created_at) VALUES (?,?,'RESULT',?,1,"
                + "'RESULT_PUBLISHED',?,0,?,'2026-10-25 00:00:00+00')",
                resultEvent, owner, result, resultDigest, eventStatus);
        return new Candidate(request, run, result, source, resultEvent, sourceEvent);
    }

    private static long account(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject("INSERT INTO app_user(username,password_hash) VALUES (?,'test-hash') "
                + "RETURNING account_id", Long.class, username);
    }

    private static long question(JdbcTemplate jdbc, long owner) {
        return jdbc.queryForObject("INSERT INTO question(account_id,request_id,subject,body_markdown,summary) "
                + "VALUES (?,?,'OTHER','题目','题目') RETURNING question_id",
                Long.class, owner, id());
    }

    private static OriginalEvent original(JdbcTemplate jdbc, String eventId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT account_id,aggregate_type,aggregate_id,"
                + "aggregate_version,kind,payload_digest,index_revision,status,created_at::text AS created_at "
                + "FROM ma_outbox WHERE event_id = ?", eventId);
        return new OriginalEvent(((Number) row.get("account_id")).longValue(),
                (String) row.get("aggregate_type"), (String) row.get("aggregate_id"),
                ((Number) row.get("aggregate_version")).longValue(), (String) row.get("kind"),
                (String) row.get("payload_digest"), ((Number) row.get("index_revision")).longValue(),
                (String) row.get("status"), (String) row.get("created_at"));
    }

    private static void assertIdentityUnchanged(JdbcTemplate jdbc, String eventId, OriginalEvent before) {
        OriginalEvent current = original(jdbc, eventId);
        assertThat(current.accountId()).isEqualTo(before.accountId());
        assertThat(current.aggregateType()).isEqualTo(before.aggregateType());
        assertThat(current.aggregateId()).isEqualTo(before.aggregateId());
        assertThat(current.aggregateVersion()).isEqualTo(before.aggregateVersion());
        assertThat(current.kind()).isEqualTo(before.kind());
        assertThat(current.payloadDigest()).isEqualTo(before.payloadDigest());
        assertThat(current.indexRevision()).isEqualTo(before.indexRevision());
        assertThat(current.createdAt()).isEqualTo(before.createdAt());
    }

    private static void assertUnchanged(JdbcTemplate jdbc, String eventId, OriginalEvent before) {
        assertThat(original(jdbc, eventId)).isEqualTo(before);
        Map<String, Object> audit = jdbc.queryForMap("SELECT retired_at,retired_reason,retired_by,"
                + "retired_from_status FROM ma_outbox WHERE event_id = ?", eventId);
        assertThat(audit.values()).allMatch(value -> value == null);
    }

    private static String sha(String text) {
        return AnswerManifestValidator.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private record Candidate(String requestId, String runId, String resultId, String sourceId,
            String resultEvent, String sourceEvent) {
    }

    private record OriginalEvent(long accountId, String aggregateType, String aggregateId,
            long aggregateVersion, String kind, String payloadDigest, long indexRevision,
            String status, String createdAt) {
    }
}
