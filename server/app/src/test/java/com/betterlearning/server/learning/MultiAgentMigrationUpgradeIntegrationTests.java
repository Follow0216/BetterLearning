package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.betterlearning.server.support.IntegrationContainers;

/** 从实际 V5 数据库升级，验证新迁移兼容已有用户、题目和当前作答。 */
@Testcontainers
@Tag("integration")
class MultiAgentMigrationUpgradeIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Test
    void v5DatabaseWithExistingLearningDataUpgradesWithoutLoss() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long account = jdbc.queryForObject("INSERT INTO app_user(username, password_hash) "
                + "VALUES ('migration_user', 'test-hash') RETURNING account_id", Long.class);
        String questionRequest = UUID.randomUUID().toString();
        Long question = jdbc.queryForObject("INSERT INTO question(account_id, request_id, subject, "
                + "body_markdown, summary) VALUES (?, ?, 'OTHER', '旧题目', '旧题目') RETURNING question_id",
                Long.class, account, questionRequest);
        String fileRef = UUID.randomUUID().toString();
        String saveId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_answer(account_id, question_id, file_ref, save_request_id, "
                + "request_hash, state) VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS')",
                account, question, fileRef, saveId, "a".repeat(64));
        String turnId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tutor_turn(turn_id, account_id, question_id, request_id, prompt, status) "
                + "VALUES (?, ?, ?, ?, '旧辅导', 'WAITING_ANSWER')",
                turnId, account, question, UUID.randomUUID().toString());

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject("SELECT username FROM app_user WHERE account_id = ?",
                String.class, account)).isEqualTo("migration_user");
        assertThat(jdbc.queryForObject("SELECT body_markdown FROM question WHERE question_id = ?",
                String.class, question)).isEqualTo("旧题目");
        assertThat(jdbc.queryForObject("SELECT file_ref FROM question_answer WHERE account_id = ? "
                + "AND question_id = ?", String.class, account, question)).isEqualTo(fileRef);
        assertThat(jdbc.queryForObject("SELECT status FROM tutor_turn WHERE turn_id = ?",
                String.class, turnId)).isEqualTo("WAITING_ANSWER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_source", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_source_version", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox WHERE kind='SOURCE_VERSION_CREATED' "
                + "AND index_revision=1 AND status='PENDING'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT v.content_sha256 FROM ma_source_version v "
                + "JOIN ma_source s ON s.source_id=v.source_id WHERE s.type='QUESTION'", String.class))
                .isEqualTo(com.betterlearning.server.learning.validation.AnswerManifestValidator.sha256(
                "旧题目".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("SELECT v.file_ref FROM ma_source_version v "
                + "JOIN ma_source s ON s.source_id=v.source_id WHERE s.type='SAVED_ANSWER'", String.class)).isEqualTo(fileRef);
        assertThat(jdbc.queryForObject("SELECT v.answer_state FROM ma_source_version v "
                + "JOIN ma_source s ON s.source_id=v.source_id WHERE s.type='SAVED_ANSWER'", String.class)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_request", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = 'ma_request'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '2026102501' AND success", Integer.class)).isEqualTo(1);
    }
}
