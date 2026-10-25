package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.betterlearning.server.learning.mapper.RetentionMapper;
import com.betterlearning.server.learning.retention.DeletionJournal;
import com.betterlearning.server.learning.retention.SourceDeletionService;
import com.betterlearning.server.support.IntegrationContainers;

import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Replays an independent deletion journal into a separate, older PostgreSQL backup. */
@Testcontainers
@Tag("integration")
class DeletionReplayIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @TempDir
    Path temporary;

    @Test
    void restoresWithdrawalsBeforeServingAndRejectsAnOlderJournal() throws Exception {
        Database live = database("deletion_live");
        Database backup = database("deletion_backup");
        long liveAccount = account(live, "live_owner");
        long backupAccount = account(backup, "backup_owner");
        assertThat(backupAccount).isEqualTo(liveAccount);

        String restoredSource = UUID.randomUUID().toString();
        String absentFromBackup = UUID.randomUUID().toString();
        source(live, liveAccount, restoredSource, 2);
        source(backup, backupAccount, restoredSource, 2);
        source(live, liveAccount, absentFromBackup, 1);

        DeletionJournal journal = DeletionJournal.initialize(temporary.resolve("independent-deletions"));
        SourceDeletionService liveDeletions = new SourceDeletionService(journal, live.mapper(), live.transactions());
        live.transactions().executeWithoutResult(status -> liveDeletions.withdraw(liveAccount, restoredSource));
        live.transactions().executeWithoutResult(status -> liveDeletions.withdraw(liveAccount, absentFromBackup));
        assertThat(journal.readAll()).hasSize(2);

        // The separately migrated backup is still from before either withdrawal.
        assertThat(activeSources(backup)).isEqualTo(1);
        assertThat(backup.jdbc().queryForObject("SELECT count(*) FROM ma_source_deletion", Long.class)).isZero();

        SourceDeletionService recovery = new SourceDeletionService(journal, backup.mapper(), backup.transactions());
        recovery.recoverBeforeServing();
        assertThat(activeSources(backup)).isZero();
        assertThat(backup.jdbc().queryForObject("SELECT status FROM ma_source WHERE source_id=?",
                String.class, restoredSource)).isEqualTo("DELETED");
        assertThat(backup.jdbc().queryForObject("SELECT title FROM ma_source WHERE source_id=?",
                String.class, restoredSource)).isEmpty();
        assertThat(backup.jdbc().queryForObject("SELECT count(*) FROM ma_source_version "
                + "WHERE source_id=? AND status='WITHDRAWN'", Long.class, restoredSource)).isEqualTo(2);
        assertThat(backup.jdbc().queryForObject("SELECT count(*) FROM ma_source_deletion", Long.class)).isEqualTo(2);
        assertThat(backup.jdbc().queryForObject("SELECT count(*) FROM ma_source_deletion "
                + "WHERE source_id=? AND account_id=? AND journal_sequence>0 AND length(journal_hash)=64",
                Long.class, absentFromBackup, backupAccount)).isEqualTo(1);
        assertThat(backup.jdbc().queryForObject("SELECT sequence FROM ma_deletion_replay WHERE singleton=TRUE",
                Long.class)).isEqualTo(2);

        recovery.recoverBeforeServing();
        assertThat(activeSources(backup)).isZero();
        assertThat(backup.jdbc().queryForObject("SELECT count(*) FROM ma_source_deletion", Long.class)).isEqualTo(2);
        assertThatThrownBy(() -> source(backup, backupAccount, absentFromBackup, 1))
                .isInstanceOf(RuntimeException.class);

        // A database watermark newer than the mounted journal must keep serving closed.
        backup.jdbc().update("UPDATE ma_deletion_replay SET sequence=3,hash=? WHERE singleton=TRUE",
                "f".repeat(64));
        assertThatThrownBy(recovery::recoverBeforeServing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("behind database watermark");
    }

    private Database database(String schema) throws Exception {
        DriverManagerDataSource administrative = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(administrative).execute("CREATE SCHEMA " + schema);
        String baseUrl = POSTGRES.getJdbcUrl();
        String separator = baseUrl.contains("?") ? "&" : "?";
        DriverManagerDataSource source = new DriverManagerDataSource(
                baseUrl + separator + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).load().migrate();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(RetentionMapper.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(source);
        factoryBean.setConfiguration(configuration);
        factoryBean.setTransactionFactory(new SpringManagedTransactionFactory());
        factoryBean.afterPropertiesSet();
        SqlSessionFactory factory = factoryBean.getObject();
        RetentionMapper mapper = new SqlSessionTemplate(factory).getMapper(RetentionMapper.class);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        return new Database(new JdbcTemplate(source), mapper, transactions);
    }

    private static long account(Database database, String name) {
        return database.jdbc().queryForObject("INSERT INTO app_user(username,password_hash) "
                + "VALUES (?,'test-hash') RETURNING account_id", Long.class, name);
    }

    private static void source(Database database, long accountId, String sourceId, int versionCount) {
        database.jdbc().update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,current_version) "
                + "VALUES (?,?,'NOTE',?,'待删除笔记',?)", sourceId, accountId, "note:" + sourceId, versionCount);
        for (int version = 1; version <= versionCount; version++) {
            database.jdbc().update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,"
                    + "body_markdown) VALUES (?,?,?,'READY',?)", sourceId, version, "a".repeat(64), "原文");
        }
    }

    private static long activeSources(Database database) {
        return database.jdbc().queryForObject("SELECT count(*) FROM ma_source WHERE status='ACTIVE'", Long.class);
    }

    private record Database(JdbcTemplate jdbc, RetentionMapper mapper, TransactionTemplate transactions) {
    }
}
