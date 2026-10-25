package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Job;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageKey;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageResult;
import com.betterlearning.server.config.MybatisConfiguration;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.Source;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceVersion;
import com.betterlearning.server.multiagent.SourceStore;

import org.flywaydb.core.Flyway;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL verifies durable index stage fencing without invoking an OCR or embedding provider. */
@Testcontainers
@Tag("integration")
class IndexStageRecoveryIntegrationTests {
    private static final DockerImageName IMAGE = DockerImageName.parse(
            "pgvector/pgvector@sha256:42e7f6b4e1eceb02ff14e3e6bc6108bbe259abbe83879dc1845d0da1ddeb555d")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("betterlearning").withUsername("test").withPassword("test");

    @Configuration(proxyBeanMethods = false)
    @MapperScan("com.betterlearning.server.retrieval.mapper")
    @EnableTransactionManagement
    static class MapperConfiguration {
    }

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static RetrievalIndexStore index;
    private static SourceStore sources;
    private long accountId;
    private String sourceId;
    private String eventId;
    private String fileRef;

    @BeforeAll
    static void database() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        sources = mock(SourceStore.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.registerBean(SourceStore.class, () -> sources);
        context.register(MybatisConfiguration.class, MapperConfiguration.class,
                MybatisPlusAutoConfiguration.class, MybatisRetrievalIndexStore.class);
        context.refresh();
        index = context.getBean(RetrievalIndexStore.class);
    }

    @AfterAll
    static void closeDatabase() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE TABLE app_user RESTART IDENTITY CASCADE");
        accountId = jdbc.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES (?,?) RETURNING account_id", Long.class, UUID.randomUUID().toString(), "test");
        sourceId = UUID.randomUUID().toString();
        eventId = UUID.randomUUID().toString();
        fileRef = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?,'SAVED_ANSWER',?,'answer','ACTIVE',1)", sourceId, accountId,
                UUID.randomUUID().toString());
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,"
                + "index_revision,file_ref) VALUES (?,1,?,'INDEX_PENDING',1,?)",
                sourceId, "a".repeat(64), fileRef);
        jdbc.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,"
                + "aggregate_version,kind,payload_digest,status,index_revision) "
                + "VALUES (?,?,'SOURCE',?,1,'SOURCE_VERSION_CREATED',?,'PENDING',1)",
                eventId, accountId, sourceId, "a".repeat(64));
        when(sources.get(anyLong(), anyString(), anyLong())).thenAnswer(invocation ->
        new SourceContent(new Source(sourceId, accountId, SourceType.SAVED_ANSWER,
                "question", "answer", "ACTIVE", 1, null),
                new SourceVersion(sourceId, 1, "a".repeat(64), "INDEX_PENDING",
                "DRAFT", fileRef, null, null, 1), List.of()));
    }

    @Test
    void confirmedOcrStagesSurviveLeaseReclaimAndUsageIsNotCountedTwice() {
        Job first = index.claim().orElseThrow();
        assertThat(index.beginIndexProtocol(first, "HANDWRITING_METHODS_V1")).isTrue();
        StageKey firstStep = key("OCR_STEP", 0);
        StageKey secondStep = key("OCR_STEP", 1);
        assertThat(index.beginIndexStage(first, firstStep).shouldCall()).isTrue();
        assertThat(index.confirmIndexStage(first, firstStep, result())).isTrue();
        assertThat(index.beginIndexStage(first, secondStep).shouldCall()).isTrue();
        assertThat(index.confirmIndexStage(first, secondStep, result())).isTrue();
        expireLease();

        Job resumed = index.claim().orElseThrow();
        assertThat(resumed.fence()).isGreaterThan(first.fence());
        assertThat(index.beginIndexStage(resumed, firstStep).confirmed()).isEqualTo(result());
        assertThat(index.beginIndexStage(resumed, secondStep).confirmed()).isEqualTo(result());
        assertThat(index.confirmIndexStage(first, firstStep, result())).isFalse();
        Anchor anchor = new Anchor("anchor-1", sourceId, 1, "HANDWRITING_STEP", 0,
                sha("x"), "{}", "x");
        StageKey embedding = new StageKey("EMBED_BATCH", 0, "a".repeat(64), fileRef,
                IndexStagePayloads.inputDigest(List.of(anchor)), "unit-model", 2,
                IndexStagePayloads.EMBEDDING_SCHEMA);
        String embeddingJson = IndexStagePayloads.encodeEmbedding(List.of(anchor),
                List.of(new double[] {1, 0}), 2);
        assertThat(index.beginIndexStage(resumed, embedding).shouldCall()).isTrue();
        assertThat(index.confirmIndexStage(resumed, embedding,
                new StageResult(embeddingJson, sha(embeddingJson), 1, 7L))).isTrue();
        assertThat(jdbc.queryForObject("SELECT ocr_calls FROM ma_index_job WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT embedding_requests FROM ma_index_job WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void unconfirmedIntentAfterCrashBecomesUnknownWithoutNewPaidCall() {
        Job first = index.claim().orElseThrow();
        assertThat(index.beginIndexProtocol(first, "HANDWRITING_METHODS_V1")).isTrue();
        assertThat(index.beginIndexStage(first, key("OCR_STEP", 0)).shouldCall()).isTrue();
        expireLease();

        assertThat(index.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM ma_index_job WHERE event_id = ?",
                String.class, eventId)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id = ?",
                Boolean.class, eventId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_index_stage WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void withdrawalBeforeResultConfirmationRejectsLateResult() {
        Job job = index.claim().orElseThrow();
        assertThat(index.beginIndexProtocol(job, "HANDWRITING_METHODS_V1")).isTrue();
        StageKey key = key("OCR_STEP", 0);
        assertThat(index.beginIndexStage(job, key).shouldCall()).isTrue();
        jdbc.update("UPDATE ma_source SET status = 'DELETED' WHERE source_id = ?", sourceId);

        assertThat(index.confirmIndexStage(job, key, result())).isFalse();
        index.unknownIndexStage(job, key);
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage "
                + "WHERE event_id = ? AND phase = 'OCR_STEP' AND ordinal = 0",
                String.class, eventId)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id = ?",
                Boolean.class, eventId)).isTrue();
    }

    @Test
    void expiredLeaseStillRecordsOriginalIntentAsUnknown() {
        Job job = index.claim().orElseThrow();
        assertThat(index.beginIndexProtocol(job, "HANDWRITING_METHODS_V1")).isTrue();
        StageKey key = key("OCR_STEP", 0);
        assertThat(index.beginIndexStage(job, key).shouldCall()).isTrue();
        expireLease();

        assertThat(index.confirmIndexStage(job, key, result())).isFalse();
        index.unknownIndexStage(job, key);
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage "
                + "WHERE event_id = ? AND phase = 'OCR_STEP' AND ordinal = 0",
                String.class, eventId)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id = ?",
                Boolean.class, eventId)).isTrue();
    }

    @Test
    void genericFailureReasonStillPreservesUnconfirmedStageCost() {
        Job job = index.claim().orElseThrow();
        assertThat(index.beginIndexProtocol(job, "HANDWRITING_METHODS_V1")).isTrue();
        assertThat(index.beginIndexStage(job, key("OCR_STEP", 0)).shouldCall()).isTrue();

        index.fail(job, "INDEX_PROCESSING_FAILED");

        assertThat(jdbc.queryForObject("SELECT failure_reason FROM ma_index_job WHERE event_id = ?",
                String.class, eventId)).isEqualTo("INDEX_PROCESSING_FAILED");
        assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id = ?",
                Boolean.class, eventId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage "
                + "WHERE event_id = ? AND phase = 'OCR_STEP' AND ordinal = 0",
                String.class, eventId)).isEqualTo("CALL_INTENT");
    }

    @Test
    void obsoletingWithdrawnSourcePreservesUnconfirmedCostFlag() {
        Job job = index.claim().orElseThrow();
        assertThat(index.beginIndexProtocol(job, "HANDWRITING_METHODS_V1")).isTrue();
        assertThat(index.beginIndexStage(job, key("OCR_STEP", 0)).shouldCall()).isTrue();
        jdbc.update("UPDATE ma_source SET status = 'DELETED' WHERE source_id = ?", sourceId);

        index.obsolete(job);

        assertThat(jdbc.queryForObject("SELECT status FROM ma_index_job WHERE event_id = ?",
                String.class, eventId)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id = ?",
                Boolean.class, eventId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_stage "
                + "WHERE event_id = ? AND phase = 'OCR_STEP' AND ordinal = 0",
                String.class, eventId)).isEqualTo("UNKNOWN");
    }

    private StageKey key(String phase, int ordinal) {
        return new StageKey(phase, ordinal, "a".repeat(64), fileRef, "b".repeat(64),
                "unit-model", "OCR_STEP".equals(phase) ? 0 : 2,
                "OCR_STEP".equals(phase) ? "handwriting-ocr-v1:math-methods-v1" : "embedding-v1");
    }

    private static StageResult result() {
        return new StageResult("{}", sha("{}"), 1, null);
    }

    private void expireLease() {
        jdbc.update("UPDATE ma_index_job SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                + "WHERE event_id = ?", eventId);
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
