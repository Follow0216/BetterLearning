package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 同一库顺序迁移两个隔离 schema，验证 pgvector 始终由 public 提供。 */
@Testcontainers
@Tag("integration")
class PgvectorSchemaMigrationIntegrationTests {
    private static final DockerImageName IMAGE = DockerImageName.parse(
            "pgvector/pgvector@sha256:42e7f6b4e1eceb02ff14e3e6bc6108bbe259abbe83879dc1845d0da1ddeb555d")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("betterlearning").withUsername("test").withPassword("test");

    @Test
    void twoTenantSchemasAndDefaultPublicUseOnePublicVectorExtension() {
        JdbcTemplate admin = new JdbcTemplate(dataSource(null));
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_extension WHERE extname='vector'",
                Integer.class)).isZero();
        admin.execute("CREATE SCHEMA p4_vector_first");
        admin.execute("CREATE SCHEMA p4_vector_second");

        int firstMigrations = migrate("p4_vector_first");
        int secondMigrations = migrate("p4_vector_second");
        int publicMigrations = migrate("public");
        assertThat(secondMigrations).isEqualTo(firstMigrations);
        assertThat(publicMigrations).isEqualTo(firstMigrations);

        assertThat(admin.queryForObject("SELECT count(*) FROM pg_extension e "
                + "JOIN pg_namespace n ON n.oid=e.extnamespace "
                + "WHERE e.extname='vector' AND n.nspname='public'", Integer.class)).isEqualTo(1);
        int publicVectorType = admin.queryForObject("SELECT 'public.vector'::regtype::oid::int", Integer.class);
        for (String schema : new String[] {"p4_vector_first", "p4_vector_second", "public"}) {
            assertThat(columnType(admin, schema, "ma_retrieval_chunk")).isEqualTo(publicVectorType);
            assertThat(columnType(admin, schema, "ma_query_embedding")).isEqualTo(publicVectorType);
            verifyVectorOperations(new JdbcTemplate(dataSource(schema)));
        }
    }

    private static int migrate(String schema) {
        DataSource dataSource = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
        if (!"public".equals(schema)) {
            assertThat(jdbc.queryForObject("SHOW search_path", String.class)).isEqualTo(schema);
        }
        var result = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).createSchemas(false).load().migrate();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",
                Integer.class)).isEqualTo(result.migrationsExecuted);
        return result.migrationsExecuted;
    }

    private static void verifyVectorOperations(JdbcTemplate jdbc) {
        long account = jdbc.queryForObject("INSERT INTO app_user(username,password_hash,role) "
                + "VALUES (?,?,?) RETURNING account_id", Long.class,
                "vector-" + UUID.randomUUID().toString().substring(0, 8), "test-hash", "USER");
        String source = UUID.randomUUID().toString();
        String anchor = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ma_source(source_id,account_id,type,origin_id,title,status,current_version) "
                + "VALUES (?,?, 'NOTE',?, 'vector fixture','ACTIVE',1)",
                source, account, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO ma_source_version(source_id,version,content_sha256,status,index_revision,"
                + "body_markdown) VALUES (?,1,?,'READY',1,'vector fixture')", source, "a".repeat(64));
        jdbc.update("INSERT INTO ma_anchor(anchor_id,source_id,version,kind,ordinal,content_sha256,"
                + "locator_json,text) VALUES (?,?,1,'MARKDOWN_BLOCK',0,?,'{}','vector fixture')",
                anchor, source, "a".repeat(64));
        jdbc.update("INSERT INTO ma_retrieval_chunk(chunk_id,account_id,source_id,version,anchor_id,"
                + "text,method_tags,terms,embedding,embedding_model,dimensions,index_revision) "
                + "VALUES (?,?,?,1,?,'vector fixture','[]'::jsonb,'[]'::jsonb,"
                + "CAST('[1,0]' AS public.vector),'test-model',2,1)",
                UUID.randomUUID().toString(), account, source, anchor);
        assertThat(jdbc.queryForObject("SELECT public.vector_dims(embedding) FROM ma_retrieval_chunk",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT embedding OPERATOR(public.<=>) "
                + "CAST('[1,0]' AS public.vector) FROM ma_retrieval_chunk", Double.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT embedding OPERATOR(public.<=>) "
                + "CAST('[0,1]' AS public.vector) FROM ma_retrieval_chunk", Double.class)).isEqualTo(1.0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ma_retrieval_chunk(chunk_id,account_id,"
                + "source_id,version,anchor_id,text,method_tags,terms,embedding,embedding_model,"
                + "dimensions,index_revision) VALUES (?,?,?,1,?,'bad dimensions','[]'::jsonb,"
                + "'[]'::jsonb,CAST('[1,0]' AS public.vector),'wrong-dim',3,1)",
                UUID.randomUUID().toString(), account, source, anchor))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, failure -> {
                    assertThat(failure.getMostSpecificCause()).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) failure.getMostSpecificCause()).getSQLState()).isEqualTo("23514");
                });
    }

    private static int columnType(JdbcTemplate jdbc, String schema, String table) {
        return jdbc.queryForObject("SELECT a.atttypid::int FROM pg_attribute a "
                + "JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relname=? AND a.attname='embedding' AND a.attnum>0",
                Integer.class, schema, table);
    }

    private static DataSource dataSource(String schema) {
        String url = POSTGRES.getJdbcUrl();
        if (schema != null) {
            url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
