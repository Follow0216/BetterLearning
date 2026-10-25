package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.betterlearning.server.learning.retention.SourceRetentionService;
import com.betterlearning.server.multiagent.MultiAgentModels.PublishSource;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Tests durable paid-stage payload retention through production withdrawal and sweep. */
@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class IndexStageRetentionNegativeTests {
    private static final Path FILE_ROOT = temporaryRoot("bl-stage-retention-files-");
    private static final Path JOURNAL_ROOT = temporaryRoot("bl-stage-retention-journal-");
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "synthetic-stage-retention-secret-value-32".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();
    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "stage-retention", "stage-retention");
        IntegrationProperties.registerRateLimits(registry);
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
        registry.add("app.files.root", FILE_ROOT::toString);
        registry.add("app.agent-retention.delete-log-root", JOURNAL_ROOT::toString);
        registry.add("app.agent-retention.initialize-journal", () -> "true");
        registry.add("app.agent-retention.sweep-enabled", () -> "false");
        registry.add("app.agent.embedding.batch-size", () -> "1");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SourceStore sources;
    @Autowired private SourceRetentionService retention;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, redis);
    }

    @Test
    void withdrawnSavedAnswerScrubsConfirmedOcrAndEmbeddingPayloadButKeepsUsageAndUnknownAudit()
            throws IOException {
        long account = jdbc.queryForObject("INSERT INTO app_user(username,password_hash) VALUES (?,?) "
                + "RETURNING account_id", Long.class, "stage-retention-" + UUID.randomUUID().toString().substring(0, 8), "test-hash");
        String fileRef = UUID.randomUUID().toString();
        Path object = FILE_ROOT.resolve("objects").resolve(fileRef);
        Files.createDirectories(object);
        Files.writeString(object.resolve("manifest.json"), "synthetic-file", StandardCharsets.UTF_8);
        var published = sources.publish(account, new PublishSource(SourceType.SAVED_ANSWER,
                UUID.randomUUID().toString(), "synthetic answer", null, "a".repeat(64), fileRef,
                "DRAFT", 0L, UUID.randomUUID().toString()));
        String sourceId = published.source().sourceId();
        String eventId = jdbc.queryForObject("SELECT event_id FROM ma_outbox WHERE account_id=? "
                + "AND aggregate_type='SOURCE' AND aggregate_id=? AND aggregate_version=1 "
                + "AND kind='SOURCE_VERSION_CREATED'", String.class, account, sourceId);
        jdbc.update("INSERT INTO ma_index_job(event_id,status,call_state,fence,index_protocol,"
                + "ocr_calls,embedding_requests,embedding_tokens,usage_uncertain) "
                + "VALUES (?,'FAILED','NOT_SENT',1,'HANDWRITING_METHODS_V1',2,1,7,true)", eventId);
        String privateTranscript = "PRIVATE-OCR-TRANSCRIPT-" + UUID.randomUUID();
        String ocrOutput = "{\"schemaVersion\":\"handwriting-ocr-v1\",\"taxonomyVersion\":"
                + "\"math-methods-v1\",\"stepOrdinal\":0,\"transcript\":\"" + privateTranscript
                + "\",\"uncertainty\":\"CLEAR\",\"methodCandidates\":[]}";
        String embeddingOutput = "{\"schemaVersion\":1,\"inputs\":[],\"vectors\":[[0.25,0.75]]}";
        stage(eventId, account, sourceId, fileRef, "OCR_STEP", 0, "ocr-model", 0,
                "handwriting-ocr-v1:math-methods-v1", ocrOutput, 1, null, "CONFIRMED");
        stage(eventId, account, sourceId, fileRef, "OCR_STEP", 1, "ocr-model", 0,
                "handwriting-ocr-v1:math-methods-v1",
                ocrOutput.replace("\"stepOrdinal\":0", "\"stepOrdinal\":1"),
                1, null, "CONFIRMED");
        stage(eventId, account, sourceId, fileRef, "EMBED_BATCH", 0, "embedding-model", 2,
                "handwriting-embedding-v1", embeddingOutput, 1, 7L, "CONFIRMED");
        // A later embedding batch failed ambiguously after earlier paid results were confirmed.
        stage(eventId, account, sourceId, fileRef, "EMBED_BATCH", 1, "embedding-model", 2,
                "handwriting-embedding-v1", null, null, null, "UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=? "
                + "AND output_json IS NOT NULL", Integer.class, eventId)).isEqualTo(3);

        sources.withdraw(account, sourceId); // Production journal + synchronous revocation.
        retention.sweep(100);               // Production DB purge, not a test UPDATE.

        assertThat(jdbc.queryForObject("SELECT database_purged_at IS NOT NULL FROM ma_source_deletion "
                + "WHERE source_id=?", Boolean.class, sourceId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT purged_at IS NOT NULL FROM ma_source_version "
                + "WHERE source_id=? AND version=1", Boolean.class, sourceId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=? "
                + "AND (output_json IS NOT NULL OR output_sha256 IS NOT NULL)", Integer.class, eventId))
                .as("OCR transcript and embedding vectors must not survive database_purged_at")
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=? "
                + "AND call_state='UNKNOWN'", Integer.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT ocr_calls,embedding_requests,embedding_tokens,usage_uncertain "
                + "FROM ma_index_job WHERE event_id=?", eventId))
                .containsEntry("ocr_calls", 2)
                .containsEntry("embedding_requests", 1)
                .containsEntry("embedding_tokens", 7L)
                .containsEntry("usage_uncertain", true);
    }

    @Test
    void unreferencedRetiredVersionScrubsStagePayloadWithoutWithdrawingCurrentVersion()
            throws IOException {
        long account = jdbc.queryForObject("INSERT INTO app_user(username,password_hash) VALUES (?,?) "
                + "RETURNING account_id", Long.class, "stage-retired-" + UUID.randomUUID().toString().substring(0, 8), "test-hash");
        String origin = UUID.randomUUID().toString();
        String oldRef = UUID.randomUUID().toString();
        Path oldObject = FILE_ROOT.resolve("objects").resolve(oldRef);
        Files.createDirectories(oldObject);
        Files.writeString(oldObject.resolve("manifest.json"), "synthetic-old-file", StandardCharsets.UTF_8);
        var first = sources.publish(account, new PublishSource(SourceType.SAVED_ANSWER,
                origin, "old answer", null, "a".repeat(64), oldRef, "DRAFT", 0L,
                UUID.randomUUID().toString()));
        String sourceId = first.source().sourceId();
        String eventId = jdbc.queryForObject("SELECT event_id FROM ma_outbox WHERE account_id=? "
                + "AND aggregate_type='SOURCE' AND aggregate_id=? AND aggregate_version=1 "
                + "AND kind='SOURCE_VERSION_CREATED'", String.class, account, sourceId);
        jdbc.update("INSERT INTO ma_index_job(event_id,status,call_state,fence,index_protocol,"
                + "ocr_calls,embedding_requests,usage_uncertain) "
                + "VALUES (?,'DONE','NOT_SENT',1,'HANDWRITING_METHODS_V1',1,0,false)", eventId);
        String transcript = "PRIVATE-OLD-VERSION-" + UUID.randomUUID();
        String output = "{\"schemaVersion\":\"handwriting-ocr-v1\",\"taxonomyVersion\":"
                + "\"math-methods-v1\",\"stepOrdinal\":0,\"transcript\":\"" + transcript
                + "\",\"uncertainty\":\"CLEAR\",\"methodCandidates\":[]}";
        stage(eventId, account, sourceId, oldRef, "OCR_STEP", 0, "ocr-model", 0,
                "handwriting-ocr-v1:math-methods-v1", output, 1, null, "CONFIRMED");
        sources.publish(account, new PublishSource(SourceType.SAVED_ANSWER,
                origin, "current answer", null, "c".repeat(64), UUID.randomUUID().toString(),
                "DRAFT", 1L, UUID.randomUUID().toString()));
        // Age is only fixture setup; purge still runs through production SourceRetentionService.
        jdbc.update("UPDATE ma_source_version SET created_at=CURRENT_TIMESTAMP - INTERVAL '31 days' "
                + "WHERE source_id=? AND version=1", sourceId);

        retention.sweep(100);

        assertThat(jdbc.queryForObject("SELECT purged_at IS NOT NULL FROM ma_source_version "
                + "WHERE source_id=? AND version=1", Boolean.class, sourceId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT purged_at IS NULL FROM ma_source_version "
                + "WHERE source_id=? AND version=2", Boolean.class, sourceId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_index_stage WHERE event_id=? "
                + "AND (output_json IS NOT NULL OR output_sha256 IS NOT NULL)", Integer.class, eventId))
                .as("old unreferenced version must lose its durable OCR copy")
                .isZero();
        assertThat(jdbc.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(1);
    }

    private void stage(String eventId, long account, String sourceId, String fileRef,
            String phase, int ordinal, String model, int dimensions, String schema,
            String output, Integer requestCount, Long tokens, String state) {
        jdbc.update("INSERT INTO ma_index_stage(event_id,phase,ordinal,account_id,source_id,source_version,"
                + "index_revision,source_sha256,file_ref,input_sha256,model,dimensions,schema_version,"
                + "call_state,intent_fence,output_json,output_sha256,request_count,total_tokens,confirmed_at) "
                + "VALUES (?,?,?,?,?,1,1,?,?,?,?,?,?,?,?,?,?,?,?,"
                + "CASE WHEN ?='CONFIRMED' THEN CURRENT_TIMESTAMP ELSE NULL END)",
                eventId, phase, ordinal, account, sourceId, "a".repeat(64), fileRef, "b".repeat(64),
                model, dimensions, schema, state, 1, output, output == null ? null : sha(output),
                requestCount, tokens, state);
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path temporaryRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
