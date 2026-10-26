package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** 仅使用本测试新建的容器库；覆盖已应用 develop 2602 的业务数据升级，不启动应用或模型。 */
@Testcontainers
@Tag("integration")
class MergedFeatureMigrationUpgradeIntegrationTests {
    private static final String DEVELOP_VERSION = "2026102602";
    private static final String EXPECTED_LATEST = "2026102605";

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Test
    void develop2602UpgradesToLatestWithoutRewritingExistingRows() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(DEVELOP_VERSION)).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT max(version) FROM flyway_schema_history "
                + "WHERE success AND version LIKE '2026%'", String.class)).isEqualTo(DEVELOP_VERSION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='ma_request' "
                + "AND column_name IN ('context_parent_request_id','requested_snapshot_kind')", Integer.class)).isZero();
        seedDevelopRows(jdbc);
        Map<String, List<Map<String, Object>>> before = legacyRows(jdbc);
        List<Map<String, Object>> oldHistory = history(jdbc);

        Flyway latest = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        assertThat(latest.getConfiguration().isOutOfOrder()).isFalse();
        assertThat(latest.getConfiguration().isValidateOnMigrate()).isTrue();
        var upgraded = latest.migrate();
        assertThat(upgraded.success).isTrue();
        assertThat(upgraded.migrationsExecuted).isEqualTo(3);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo(EXPECTED_LATEST);
        assertThat(legacyRows(jdbc)).isEqualTo(before);
        assertThat(history(jdbc).subList(0, oldHistory.size())).isEqualTo(oldHistory);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success "
                + "AND version IN ('2026102603','2026102604','2026102605') ORDER BY installed_rank", String.class))
                .containsExactly("2026102603", "2026102604", "2026102605");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE version='2026102601'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT context_parent_request_id IS NULL "
                + "AND conversation_context_json IS NULL AND requested_snapshot_kind=snapshot_kind "
                + "AND input_selection_reason IS NULL AND question_markdown IS NULL FROM ma_request",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_tablet_presence", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ocr_batch", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT tgname FROM pg_trigger WHERE NOT tgisinternal "
                + "AND tgname IN ('ma_conversation_source_withdrawn','ma_conversation_version_withdrawn')",
                String.class)).containsExactlyInAnyOrder(
                "ma_conversation_source_withdrawn", "ma_conversation_version_withdrawn");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_attempt", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_outbox", Integer.class)).isZero();

        List<Map<String, Object>> upgradedHistory = history(jdbc);
        var replay = latest.migrate();
        assertThat(replay.success).isTrue();
        assertThat(replay.migrationsExecuted).isZero();
        assertThat(legacyRows(jdbc)).isEqualTo(before);
        assertThat(history(jdbc)).isEqualTo(upgradedHistory);
    }

    private static void seedDevelopRows(JdbcTemplate jdbc) {
        Long account = jdbc.queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES ('merged_upgrade_user','synthetic-hash') RETURNING account_id", Long.class);
        Long question = jdbc.queryForObject("INSERT INTO question(account_id,request_id,subject,body_markdown,summary) "
                + "VALUES (?,?,'OTHER','旧题面 $2x+3=17$','旧题面') RETURNING question_id",
                Long.class, account, id());
        // 既有 NEW_CAPTURE 尚未冻结的请求是合法旧行；迁移不得把它改判为 NO_ANSWER。
        jdbc.update("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,status,input_deadline_at) "
                + "VALUES (?,?,?,'旧问题','HINT','NEW_CAPTURE','WAITING_INPUT',TIMESTAMPTZ '2099-01-01 00:00:00+00')",
                id(), account, question);
        String note = id();
        String source = id();
        String book = id();
        NoteContent content = NoteContent.validate("旧笔记", "原正文 $x^2$\r\n第二行");
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,current_version) "
                + "VALUES (?,?,'NOTE',?,?,1)", source, account, note, content.title());
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,body_markdown) "
                + "VALUES (?,1,?,?)", source, content.contentSha256(), content.bodyMarkdown());
        jdbc.update("INSERT INTO ma_note_version(source_id,version,title) VALUES (?,1,?)", source, content.title());
        jdbc.update("INSERT INTO ma_note_request(account_id,idempotency_key,note_id,source_id,request_sha256) "
                + "VALUES (?,?,?,?,?)", account, id(), note, source, content.requestSha256());
        jdbc.update("INSERT INTO ma_notebook(account_id,notebook_id,name) VALUES (?,?,'原笔记本')", account, book);
        jdbc.update("INSERT INTO ma_note_context(account_id,note_id,notebook_id,question_id) VALUES (?,?,?,?)",
                account, note, book, question);
    }

    private static Map<String, List<Map<String, Object>>> legacyRows(JdbcTemplate jdbc) {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("app_user", "question", "ma_source", "ma_source_version",
                "ma_note_version", "ma_note_request", "ma_notebook", "ma_note_context")) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }
        rows.put("ma_request", jdbc.queryForList("SELECT request_id,account_id,question_id,prompt,mode,snapshot_kind,"
                + "source_request_id,snapshot_origin_request_id,snapshot_file_ref,snapshot_sha256,snapshot_source_id,"
                + "snapshot_version,current_run_id,status,revision,input_deadline_at,created_at,updated_at FROM ma_request"));
        return rows;
    }

    private static List<Map<String, Object>> history(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT installed_rank,version,description,type,script,checksum,installed_by,"
                + "installed_on,execution_time,success FROM flyway_schema_history ORDER BY installed_rank");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
