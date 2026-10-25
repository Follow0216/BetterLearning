package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

/** A real V09 database upgrade checks Java migration discovery and fail-closed method projection. */
@Testcontainers
@Tag("integration")
class HandwritingMethodProjectionMigrationIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Test
    void backfillsOnlyValidatedPositiveMethodsWithoutChangingRawOcr() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2026102509")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long owner = jdbc.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES (?,'test-hash') RETURNING account_id", Long.class, id().replace("-", ""));
        long other = jdbc.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES (?,'test-hash') RETURNING account_id", Long.class, id().replace("-", ""));

        Fixture formula = oldStructuredRow(jdbc, owner, "$$x^2+2x=(x+1)^2-1$$", true, owner);
        Fixture negated = oldStructuredRow(jdbc, owner, "不采用配方法，改用展开式。", true, owner);
        Fixture brokenBinding = oldStructuredRow(jdbc, owner, "$$y=(x+1)^2$$", true, other);
        Fixture literal = oldStructuredRow(jdbc, owner, "使用配方法完成平方。", false, owner);
        Map<String, String> raw = Map.of(formula.sourceId(), formula.rawMetadata(),
                negated.sourceId(), negated.rawMetadata(),
                brokenBinding.sourceId(), brokenBinding.rawMetadata(),
                literal.sourceId(), literal.rawMetadata());

        Flyway upgrade = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2026102510")).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        Map<String, Object> history = jdbc.queryForMap("SELECT script,type,checksum,success "
                + "FROM flyway_schema_history WHERE version='2026102510'");
        assertThat(history.get("script").toString())
                .contains("V2026102510__handwriting_method_projection");
        assertThat(history.get("type")).isEqualTo("JDBC");
        assertThat(history.get("checksum")).isNotNull();
        assertThat(history.get("success")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema=current_schema() AND table_name='ma_retrieval_chunk' "
                + "AND column_name='inferred_method_tags'", String.class)).isEqualTo("jsonb");
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema=current_schema() AND table_name='ma_retrieval_chunk' "
                + "AND column_name='inferred_method_tags'", String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema=current_schema() AND table_name='ma_retrieval_chunk' "
                + "AND column_name='method_projection_hash'", Integer.class)).isEqualTo(32);

        assertProjection(jdbc, formula, "[\"配方法\"]", "[]", true);
        assertProjection(jdbc, negated, "[]", "[]", true);
        assertProjection(jdbc, brokenBinding, "[]", "[]", false);
        assertProjection(jdbc, literal, "[]", "[\"配方法\"]", true);
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            assertThat(jdbc.queryForObject("SELECT method_metadata::text FROM ma_retrieval_chunk "
                    + "WHERE source_id=?", String.class, entry.getKey())).isEqualTo(entry.getValue());
        }
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    private static Fixture oldStructuredRow(JdbcTemplate jdbc, long owner, String text,
            boolean candidate, long metadataAccount) {
        String source = id();
        String anchor = id();
        String chunk = id();
        String event = id();
        String digest = sha(text);
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?,'SAVED_ANSWER',?,'手写历史','ACTIVE',1)", source, owner, id());
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,index_revision,"
                + "file_ref) VALUES (?,1,?,'READY',1,?)", source, "a".repeat(64), id());
        jdbc.update("INSERT INTO ma_anchor(anchor_id,source_id,version,kind,ordinal,content_sha256,"
                + "locator_json,text) VALUES (?,?,1,'HANDWRITING_STEP',0,?,'{}',?)",
                anchor, source, digest, text);
        jdbc.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,"
                + "aggregate_version,kind,payload_digest,index_revision,status) "
                + "VALUES (?,?,'SOURCE',?,1,'SOURCE_VERSION_CREATED',?,1,'DELIVERED')",
                event, owner, source, "a".repeat(64));
        jdbc.update("INSERT INTO ma_index_job(event_id,status,call_state,index_protocol) "
                + "VALUES (?,'DONE','NOT_SENT','HANDWRITING_METHODS_V1')", event);
        HandwritingIndexOcr.Result result = new HandwritingIndexOcr.Result(
                HandwritingIndexOcr.SCHEMA_VERSION, HandwritingMethodTaxonomy.VERSION, 0,
                text, "CLEAR", candidate ? List.of(new HandwritingIndexOcr.MethodCandidate(
                "配方法", 0, text.length(), new HandwritingIndexOcr.Region(0, 0, 1, 1),
                "CANDIDATE", "VISIBLE_STEPS")) : List.of());
        String metadata = new HandwritingMethodMetadata(HandwritingMethodMetadata.FORMAT_VERSION,
                metadataAccount, source, 1, 1, anchor, digest, "b".repeat(64),
                10, 10, "old-structured-ocr", result).encode();
        jdbc.update("INSERT INTO ma_retrieval_chunk(chunk_id,account_id,source_id,version,anchor_id,"
                + "text,method_tags,terms,embedding,embedding_model,dimensions,index_revision,method_metadata) "
                + "VALUES (?,?,?,1,?,?,'[]'::jsonb,'[]'::jsonb,CAST('[1,0]' AS public.vector),"
                + "'test-model',2,1,CAST(? AS jsonb))",
                chunk, owner, source, anchor, text, metadata);
        String stored = jdbc.queryForObject("SELECT method_metadata::text FROM ma_retrieval_chunk "
                + "WHERE source_id=?", String.class, source);
        return new Fixture(source, stored);
    }

    private static void assertProjection(JdbcTemplate jdbc, Fixture fixture, String inferred,
            String literal, boolean validBinding) {
        Map<String, Object> row = jdbc.queryForMap("SELECT inferred_method_tags::text AS inferred,"
                + "method_tags::text AS literal,method_projection_hash,md5(method_metadata::text) AS raw_hash "
                + "FROM ma_retrieval_chunk WHERE source_id=?", fixture.sourceId());
        assertThat(row.get("inferred")).isEqualTo(inferred);
        assertThat(row.get("literal")).isEqualTo(literal);
        if (validBinding) {
            assertThat(row.get("method_projection_hash")).isEqualTo(row.get("raw_hash"));
            assertThat(row.get("method_projection_hash").toString()).hasSize(32);
        } else {
            assertThat(row.get("method_projection_hash")).isNull();
        }
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(String sourceId, String rawMetadata) { }
}
