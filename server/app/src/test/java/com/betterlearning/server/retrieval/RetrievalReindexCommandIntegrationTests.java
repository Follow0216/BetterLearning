package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 子进程执行真实 CLI 与 PostgreSQL；测试期间不装配 worker、HTTP 或模型客户端。 */
@Testcontainers
@Tag("integration")
class RetrievalReindexCommandIntegrationTests {
    private static final DockerImageName IMAGE = DockerImageName.parse(
            "pgvector/pgvector@sha256:42e7f6b4e1eceb02ff14e3e6bc6108bbe259abbe83879dc1845d0da1ddeb555d")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("betterlearning").withUsername("test").withPassword("test");

    private static JdbcTemplate jdbc;

    @TempDir
    Path filesRoot;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void subprocessQueuesOneNewRevisionAndNeverStartsIndexWorker() throws Exception {
        long account = account();
        String source = source(account, 1, "ACTIVE");

        Result result = invoke(account, source, 1, 1);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("REINDEX_QUEUED", "indexRevision=2");
        assertThat(revision(source, 1)).isEqualTo(2);
        assertThat(status(source, 1)).isEqualTo("INDEX_PENDING");
        assertThat(events(source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_index_job", Integer.class)).isZero();
    }

    @Test
    void staleRevisionAndNonCurrentVersionDoNotCreateEvent() throws Exception {
        long account = account();
        String stale = source(account, 1, "ACTIVE");
        assertThat(invoke(account, stale, 1, 1).exitCode()).isZero();
        jdbc.update("UPDATE ma_source_version SET status='READY' WHERE source_id=? AND version=1", stale);

        Result replay = invoke(account, stale, 1, 1);

        assertThat(replay.exitCode()).isEqualTo(3);
        assertThat(replay.error()).contains("SOURCE_STALE");
        assertThat(events(stale)).isEqualTo(1);

        String older = source(account, 2, "ACTIVE");
        Result oldVersion = invoke(account, older, 1, 1);
        assertThat(oldVersion.exitCode()).isEqualTo(3);
        assertThat(oldVersion.error()).contains("SOURCE_STALE");
        assertThat(events(older)).isZero();
    }

    @Test
    void withdrawnAndOtherAccountCannotQueueEvent() throws Exception {
        long owner = account();
        long other = account();
        String withdrawn = source(owner, 1, "DELETED");
        String privateSource = source(owner, 1, "ACTIVE");

        Result removed = invoke(owner, withdrawn, 1, 1);
        Result crossAccount = invoke(other, privateSource, 1, 1);

        assertThat(removed.exitCode()).isEqualTo(3);
        assertThat(removed.error()).contains("SOURCE_NOT_FOUND");
        assertThat(crossAccount.exitCode()).isEqualTo(3);
        assertThat(crossAccount.error()).contains("SOURCE_NOT_FOUND");
        assertThat(events(withdrawn)).isZero();
        assertThat(events(privateSource)).isZero();
    }

    private Result invoke(long account, String source, long version, long revision) throws Exception {
        String configuredJar = System.getProperty("p4.reindex.jar");
        String configuredScript = System.getProperty("p4.reindex.script");
        assertThat(configuredJar == null).isEqualTo(configuredScript == null);
        List<String> command = new ArrayList<>();
        if (configuredJar != null) {
            Path jar = Path.of(configuredJar);
            Path script = Path.of(configuredScript);
            assertThat(jar.isAbsolute() && Files.isRegularFile(jar)).isTrue();
            assertThat(script.isAbsolute() && Files.isRegularFile(script)).isTrue();
            command.add("/bin/bash");
            command.add(script.toString());
        } else {
            String classPath = System.getProperty("surefire.test.class.path",
                    System.getProperty("java.class.path"));
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            command.addAll(List.of(java, "-cp", classPath, RetrievalReindexCommand.class.getName()));
        }
        command.addAll(List.of("--account", Long.toString(account), "--source", source,
                "--version", Long.toString(version), "--expected-index-revision", Long.toString(revision),
                "--confirm-new-paid-attempt"));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        environment.put("APP_FILES_ROOT", filesRoot.toString());
        environment.put("PATH", Path.of(System.getProperty("java.home"), "bin") + ":/usr/bin:/bin");
        if (configuredJar != null) {
            environment.put("SERVER_APP_JAR", configuredJar);
        }
        builder.redirectErrorStream(false);
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("reindex subprocess timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.exitValue(), output, error);
    }

    private static long account() {
        return jdbc.queryForObject("INSERT INTO app_user(username,password_hash,role) "
                + "VALUES (?,?,?) RETURNING account_id", Long.class,
                "reindex-" + UUID.randomUUID().toString().substring(0, 8), "test-hash", "USER");
    }

    private static String source(long account, long currentVersion, String state) {
        String source = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?,?,?,?,?,?)", source, account, "NOTE", UUID.randomUUID().toString(),
                "note", state, currentVersion);
        for (long version = 1; version <= currentVersion; version++) {
            jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,index_revision,"
                    + "body_markdown) VALUES (?,?,?,?,?,?)", source, version, "a".repeat(64),
                    "READY", 1, "正文");
        }
        return source;
    }

    private static long revision(String source, long version) {
        return jdbc.queryForObject("SELECT index_revision FROM ma_source_version "
                + "WHERE source_id=? AND version=?", Long.class, source, version);
    }

    private static String status(String source, long version) {
        return jdbc.queryForObject("SELECT status FROM ma_source_version WHERE source_id=? AND version=?",
                String.class, source, version);
    }

    private static int events(String source) {
        return jdbc.queryForObject("SELECT count(*) FROM ma_outbox WHERE aggregate_id=? "
                + "AND kind='SOURCE_REINDEX'", Integer.class, source);
    }

    private record Result(int exitCode, String output, String error) {
    }
}
