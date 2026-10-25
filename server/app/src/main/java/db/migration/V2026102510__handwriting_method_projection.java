package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;

import com.betterlearning.server.agent.retrieval.handwriting.HandwritingMethodMetadata;
import com.betterlearning.server.agent.retrieval.ranking.ChineseMathTerms;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import tools.jackson.databind.json.JsonMapper;

/**
 * Uses the server's eligibility rules for existing rows as well as newly indexed rows.
 * No provider call, anchor rewrite, or embedding rebuild is involved.
 */
public class V2026102510__handwriting_method_projection extends BaseJavaMigration {
    @Override
    public Integer getChecksum() {
        // Frozen Java migration revision. Later rule changes require a new migration.
        return 1;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement schema = connection.createStatement()) {
            schema.execute("""
                    ALTER TABLE ma_retrieval_chunk
                        ADD COLUMN inferred_method_tags JSONB NOT NULL DEFAULT '[]'::jsonb
                            CHECK (jsonb_typeof(inferred_method_tags) = 'array'),
                        ADD COLUMN method_projection_hash CHAR(32)
                    """);
        }
        JsonMapper json = JsonMapper.builder().build();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT c.chunk_id,c.account_id,c.source_id,c.version,c.index_revision,c.anchor_id,
                    c.text,a.content_sha256,c.method_metadata::text AS metadata
                FROM ma_retrieval_chunk c
                JOIN ma_source s ON s.source_id=c.source_id AND s.account_id=c.account_id
                JOIN ma_anchor a ON a.anchor_id=c.anchor_id AND a.source_id=c.source_id
                    AND a.version=c.version
                WHERE s.type='SAVED_ANSWER' AND c.method_metadata IS NOT NULL
                ORDER BY c.chunk_id
                """);
                PreparedStatement update = connection.prepareStatement("""
                        UPDATE ma_retrieval_chunk SET method_tags=CAST(? AS jsonb),
                            inferred_method_tags=CAST(? AS jsonb),
                            method_projection_hash=CASE WHEN ? THEN md5(method_metadata::text) ELSE NULL END
                        WHERE chunk_id=? AND account_id=?
                        """)) {
            select.setFetchSize(256);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Set<String> eligible = Set.of();
                    boolean valid = false;
                    try {
                        HandwritingMethodMetadata metadata = HandwritingMethodMetadata.parse(rows.getString("metadata"));
                        metadata.validateBindings(rows.getLong("account_id"), rows.getString("source_id"),
                                rows.getLong("version"), rows.getLong("index_revision"), rows.getString("anchor_id"),
                                rows.getString("content_sha256"), rows.getString("text"));
                        eligible = metadata.eligibleMethods();
                        valid = true;
                    } catch (IllegalArgumentException ignored) {
                        // Unbound/malformed legacy metadata is retained, but cannot supply inferred recall.
                    }
                    update.setString(1, json.writeValueAsString(ChineseMathTerms.methodsInText(rows.getString("text"))));
                    update.setString(2, json.writeValueAsString(eligible));
                    update.setBoolean(3, valid);
                    update.setString(4, rows.getString("chunk_id"));
                    update.setLong(5, rows.getLong("account_id"));
                    update.executeUpdate();
                }
            }
        }
    }
}
