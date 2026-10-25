package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.betterlearning.server.agent.retrieval.handwriting.HandwritingMethodMetadata;
import com.betterlearning.server.agent.retrieval.ranking.HandwritingMethodTaxonomy;
import com.betterlearning.server.agent.service.HandwritingIndexOcr;
import com.betterlearning.server.support.IntegrationContainers;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Draft: one real PostgreSQL V08 -> V09 -> V10 upgrade chain, with measured lock wait. */
@Testcontainers
@Tag("integration")
class IndexStageUpgradeChainIntegrationTests {
    private static final String SOURCE_SHA = "a".repeat(64);
    private static final String OUTPUT = "{\"transcript\":\"private handwritten solution\"}";

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Test
    void v08PurgedPayloadIsBackfilledByV09AndV10ProjectsOnlyBoundMetadata() throws Exception {
        String schema = "p6_chain_" + UUID.randomUUID().toString().replace("-", "");
        DataSource adminSource = source(null);
        new JdbcTemplate(adminSource).execute("CREATE SCHEMA " + schema);
        DataSource dataSource = source(schema);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("2026102507")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT to_regclass('ma_index_stage') IS NULL",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE version='2026102507' AND success", Integer.class)).isEqualTo(1);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("2026102508")).load().migrate();
        assertThat(jdbc.queryForObject("SELECT to_regclass('ma_index_stage') IS NOT NULL",
                Boolean.class)).isTrue();
        long account = account(jdbc);
        StageFixture erased = stageFixture(jdbc, account);
        ProjectionFixture projection = projectionFixture(jdbc, account,
                "$$x^2+2x=(x+1)^2-1$$");
        ProjectionFixture negated = projectionFixture(jdbc, account,
                "不采用配方法，改用展开式。");

        // These are legitimate V08 public deletion functions. V08 leaves the stage payload behind.
        jdbc.queryForObject("SELECT ma_apply_source_deletion(?,?,CURRENT_TIMESTAMP,1,?)",
                Boolean.class, erased.source(), account, "f".repeat(64));
        jdbc.queryForObject("SELECT ma_scrub_source_version(?,1)",
                Boolean.class, erased.source());
        jdbc.update("UPDATE ma_source_deletion SET database_purged_at=CURRENT_TIMESTAMP "
                + "WHERE source_id=?", erased.source());
        assertThat(jdbc.queryForObject("SELECT purged_at IS NOT NULL FROM ma_source_version "
                + "WHERE source_id=? AND version=1", Boolean.class, erased.source())).isTrue();
        assertThat(jdbc.queryForObject("SELECT output_json FROM ma_index_stage WHERE event_id=?",
                String.class, erased.event())).isEqualTo(OUTPUT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE version IN ('2026102507','2026102508') AND success", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE version IN ('2026102509','2026102510')", Integer.class)).isZero();

        // Hold exactly the job row V09's scrub function must lock; observe real pg_stat_activity.
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement(
                    "SELECT event_id FROM ma_index_job WHERE event_id=? FOR UPDATE")) {
                lock.setString(1, erased.event());
                lock.executeQuery().close();
            }
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                long migrationStarted = System.nanoTime();
                Future<?> migration = executor.submit(() -> {
                    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                            .schemas(schema).defaultSchema(schema)
                            .target(MigrationVersion.fromVersion("2026102509")).load().migrate();
                });
                long waitObserved = awaitDatabaseLock(jdbc, schema, migration,
                        "%ma_scrub_index_stages%");
                // Test-induced hold, not an estimate of migration cost on production data.
                Thread.sleep(250);
                long lockReleased = System.nanoTime();
                holder.commit();
                migration.get(20, TimeUnit.SECONDS);
                long migrationCompleted = System.nanoTime();
                long observedWaitMs = Duration.ofNanos(lockReleased - waitObserved).toMillis();
                long migrationWallMs = Duration.ofNanos(migrationCompleted - migrationStarted).toMillis();
                long afterReleaseMs = Duration.ofNanos(migrationCompleted - lockReleased).toMillis();
                assertThat(observedWaitMs).isGreaterThanOrEqualTo(200);
                assertThat(migrationWallMs).isGreaterThanOrEqualTo(observedWaitMs);
                System.out.println("P6_V09_LOCK_MEASUREMENT observedWaitMs=" + observedWaitMs
                        + " migrationWallMs=" + migrationWallMs + " afterReleaseMs=" + afterReleaseMs
                        + " rows=1 schema=" + schema);
            } finally {
                holder.rollback();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=? "
                + "AND purged_at IS NOT NULL AND input_sha256 IS NULL AND output_json IS NULL "
                + "AND output_sha256 IS NULL", Integer.class, erased.event())).isEqualTo(1);
        Map<String, Object> stage = jdbc.queryForMap("SELECT call_state,request_count,total_tokens "
                + "FROM ma_index_stage WHERE event_id=?", erased.event());
        assertThat(stage.get("call_state")).isEqualTo("CONFIRMED");
        assertThat(stage.get("request_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id=?",
                Integer.class, erased.event())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT index_revision FROM ma_source_version "
                + "WHERE source_id=? AND version=1", Long.class, erased.source())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE version='2026102509' AND success", Integer.class)).isEqualTo(1);

        Flyway finish = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("2026102510")).load();
        // SELECT FOR UPDATE holds PostgreSQL RowShareLock on ma_retrieval_chunk; V10's
        // ALTER TABLE needs AccessExclusiveLock, so this is a real DDL lock wait.
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement(
                    "SELECT chunk_id FROM ma_retrieval_chunk WHERE chunk_id=? FOR UPDATE")) {
                lock.setString(1, projection.chunk());
                lock.executeQuery().close();
            }
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                long migrationStarted = System.nanoTime();
                Future<?> migration = executor.submit(() -> finish.migrate());
                long waitObserved = awaitDatabaseLock(jdbc, schema, migration,
                        "%ALTER TABLE ma_retrieval_chunk%");
                Thread.sleep(250);
                long lockReleased = System.nanoTime();
                holder.commit();
                migration.get(20, TimeUnit.SECONDS);
                long migrationCompleted = System.nanoTime();
                long observedWaitMs = Duration.ofNanos(lockReleased - waitObserved).toMillis();
                long migrationWallMs = Duration.ofNanos(migrationCompleted - migrationStarted).toMillis();
                long afterReleaseMs = Duration.ofNanos(migrationCompleted - lockReleased).toMillis();
                assertThat(observedWaitMs).isGreaterThanOrEqualTo(200);
                assertThat(migrationWallMs).isGreaterThanOrEqualTo(observedWaitMs);
                System.out.println("P6_V10_LOCK_MEASUREMENT observedWaitMs=" + observedWaitMs
                        + " migrationWallMs=" + migrationWallMs + " afterReleaseMs=" + afterReleaseMs
                        + " rows=2 schema=" + schema);
            } finally {
                holder.rollback();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
        Map<String, Object> chunk = jdbc.queryForMap("SELECT method_tags::text AS literal,"
                + "inferred_method_tags::text AS inferred,method_projection_hash,"
                + "md5(method_metadata::text) AS raw_hash,method_metadata::text AS raw "
                + "FROM ma_retrieval_chunk WHERE chunk_id=?", projection.chunk());
        assertThat(chunk.get("literal")).isEqualTo("[]");
        assertThat(chunk.get("inferred")).isEqualTo("[\"配方法\"]");
        assertThat(chunk.get("method_projection_hash")).isEqualTo(chunk.get("raw_hash"));
        assertThat(chunk.get("raw")).isEqualTo(projection.rawMetadata());
        Map<String, Object> negative = jdbc.queryForMap("SELECT method_tags::text AS literal,"
                + "inferred_method_tags::text AS inferred,method_projection_hash,"
                + "md5(method_metadata::text) AS raw_hash,method_metadata::text AS raw "
                + "FROM ma_retrieval_chunk WHERE chunk_id=?", negated.chunk());
        assertThat(negative.get("literal")).isEqualTo("[]");
        assertThat(negative.get("inferred")).isEqualTo("[]");
        assertThat(negative.get("method_projection_hash")).isEqualTo(negative.get("raw_hash"));
        assertThat(negative.get("raw")).isEqualTo(negated.rawMetadata());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE version='2026102510' AND success", Integer.class)).isEqualTo(1);
        assertThat(finish.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=? "
                + "AND output_json IS NOT NULL", Integer.class, erased.event())).isZero();
    }

    private static long awaitDatabaseLock(JdbcTemplate jdbc, String schema, Future<?> migration,
            String queryPattern)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (migration.isDone()) {
                migration.get();
                throw new AssertionError("V09 migration finished before job lock wait");
            }
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE pid<>pg_backend_pid() AND datname=current_database() "
                    + "AND wait_event_type='Lock' AND query LIKE ?",
                    Integer.class, queryPattern);
            if (waiting != null && waiting > 0) {
                return System.nanoTime();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No V09 job-row lock wait observed in schema " + schema);
    }

    private static StageFixture stageFixture(JdbcTemplate jdbc, long account) {
        String source = id();
        String event = id();
        String fileRef = id();
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?,'SAVED_ANSWER',?,'private','ACTIVE',1)", source, account, id());
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,index_revision,"
                + "file_ref) VALUES (?,1,?,'READY',1,?)", source, SOURCE_SHA, fileRef);
        jdbc.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,"
                + "aggregate_version,kind,payload_digest,status,index_revision) "
                + "VALUES (?,?,'SOURCE',?,1,'SOURCE_VERSION_CREATED',?,'PENDING',1)",
                event, account, source, SOURCE_SHA);
        jdbc.update("INSERT INTO ma_index_job(event_id,status,call_state,fence,index_protocol,ocr_calls) "
                + "VALUES (?,'DONE','NOT_SENT',7,'HANDWRITING_METHODS_V1',1)", event);
        jdbc.update("INSERT INTO ma_index_stage(event_id,phase,ordinal,account_id,source_id,"
                + "source_version,index_revision,source_sha256,file_ref,input_sha256,model,"
                + "dimensions,schema_version,call_state,intent_fence,output_json,output_sha256,"
                + "request_count,confirmed_at) VALUES (?,'OCR_STEP',0,?,?,1,1,?,?,?,'ocr-model',0,"
                + "'handwriting-ocr-v1:math-methods-v1','CONFIRMED',7,?,?,1,CURRENT_TIMESTAMP)",
                event, account, source, SOURCE_SHA, fileRef, "b".repeat(64), OUTPUT, sha(OUTPUT));
        return new StageFixture(source, event);
    }

    private static ProjectionFixture projectionFixture(JdbcTemplate jdbc, long account, String text) {
        String source = id();
        String event = id();
        String anchor = id();
        String chunk = id();
        String digest = sha(text);
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?,'SAVED_ANSWER',?,'live','ACTIVE',1)", source, account, id());
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,index_revision,"
                + "file_ref) VALUES (?,1,?,'READY',1,?)", source, SOURCE_SHA, id());
        jdbc.update("INSERT INTO ma_anchor(anchor_id,source_id,version,kind,ordinal,content_sha256,"
                + "locator_json,text) VALUES (?,?,1,'HANDWRITING_STEP',0,?,'{}',?)",
                anchor, source, digest, text);
        jdbc.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,"
                + "aggregate_version,kind,payload_digest,index_revision,status) "
                + "VALUES (?,?,'SOURCE',?,1,'SOURCE_VERSION_CREATED',?,1,'DELIVERED')",
                event, account, source, SOURCE_SHA);
        jdbc.update("INSERT INTO ma_index_job(event_id,status,call_state,index_protocol) "
                + "VALUES (?,'DONE','NOT_SENT','HANDWRITING_METHODS_V1')", event);
        HandwritingIndexOcr.Result result = new HandwritingIndexOcr.Result(
                HandwritingIndexOcr.SCHEMA_VERSION, HandwritingMethodTaxonomy.VERSION, 0,
                text, "CLEAR", List.of(new HandwritingIndexOcr.MethodCandidate("配方法", 0,
                text.length(), new HandwritingIndexOcr.Region(0, 0, 1, 1), "CANDIDATE", "VISIBLE_STEPS")));
        String metadata = new HandwritingMethodMetadata(HandwritingMethodMetadata.FORMAT_VERSION,
                account, source, 1, 1, anchor, digest, "b".repeat(64), 10, 10,
                "old-structured-ocr", result).encode();
        jdbc.update("INSERT INTO ma_retrieval_chunk(chunk_id,account_id,source_id,version,anchor_id,"
                + "text,method_tags,terms,embedding,embedding_model,dimensions,index_revision,method_metadata) "
                + "VALUES (?,?,?,1,?,?,'[]'::jsonb,'[]'::jsonb,CAST('[1,0]' AS public.vector),"
                + "'test-model',2,1,CAST(? AS jsonb))",
                chunk, account, source, anchor, text, metadata);
        String raw = jdbc.queryForObject("SELECT method_metadata::text FROM ma_retrieval_chunk "
                + "WHERE chunk_id=?", String.class, chunk);
        return new ProjectionFixture(chunk, raw);
    }

    private static long account(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES (?,'test-hash') RETURNING account_id", Long.class,
                UUID.randomUUID().toString().replace("-", ""));
    }

    private static DataSource source(String schema) {
        String url = POSTGRES.getJdbcUrl();
        if (schema != null) {
            url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private record StageFixture(String source, String event) { }

    private record ProjectionFixture(String chunk, String rawMetadata) { }
}
