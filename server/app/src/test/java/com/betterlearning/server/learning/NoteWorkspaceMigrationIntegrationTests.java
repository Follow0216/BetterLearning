package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.betterlearning.server.learning.validation.NoteContent;
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

/** 已有笔记的无损升级，保留旧幂等指纹、来源标识和所有正文版本。 */
@Testcontainers
@Tag("integration")
class NoteWorkspaceMigrationIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Test
    void existingNotesGainUnfiledMetadataWithoutRewritingSourcesOrIdempotency() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2026102509")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long account = jdbc.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES ('note_migration_user','synthetic-hash') RETURNING account_id", Long.class);
        String source = UUID.randomUUID().toString();
        String note = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        NoteContent content = NoteContent.validate("旧标题", "原文 $x^2$\r\n下一行");
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,current_version) "
                + "VALUES (?,?,'NOTE',?,'旧标题',1)", source, account, note);
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,body_markdown) "
                + "VALUES (?,1,?,?)", source, content.contentSha256(), content.bodyMarkdown());
        jdbc.update("INSERT INTO ma_note_version(source_id,version,title) VALUES (?,1,?)", source, content.title());
        jdbc.update("INSERT INTO ma_note_request(account_id,idempotency_key,note_id,source_id,request_sha256) "
                + "VALUES (?,?,?,?,?)", account, key, note, source, content.requestSha256());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_note_context WHERE account_id=? "
                + "AND note_id=? AND notebook_id IS NULL AND question_id IS NULL AND revision=1", Integer.class,
                account, note)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_sha256 FROM ma_note_request WHERE account_id=? "
                + "AND idempotency_key=?", String.class, account, key)).isEqualTo(content.requestSha256());
        assertThat(jdbc.queryForObject("SELECT body_markdown FROM ma_source_version WHERE source_id=? "
                + "AND version=1", String.class, source)).isEqualTo(content.bodyMarkdown());
        assertThat(jdbc.queryForObject("SELECT origin_id FROM ma_source WHERE source_id=?", String.class,
                source)).isEqualTo(note);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_source_version", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_outbox", Integer.class)).isZero();
    }
}
