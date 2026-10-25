package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import com.betterlearning.server.learning.retention.SourceRetentionService;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.MultiAgentModels.*;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;
import org.junit.jupiter.api.*;
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

@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class SourceRetentionIntegrationTests {
    private static final Path ROOT = temporaryRoot();
    private static final Path JOURNAL_ROOT = temporaryRoot();
    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();
    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, Base64.getEncoder().encodeToString(
                "retention-integration-only-secret-value-32".getBytes(StandardCharsets.UTF_8)), "retention", "retention");
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
        registry.add("app.files.root", ROOT::toString);
        registry.add("app.agent-retention.delete-log-root", JOURNAL_ROOT::toString);
        registry.add("app.agent-retention.initialize-journal", () -> "true");
        registry.add("app.agent-retention.sweep-enabled", () -> "false");
    }
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SourceStore sources;
    @Autowired
    private SourceRetentionService retention;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, redis);
    }

    @Test
    void withdrawalImmediatelyDeniesEveryVersionAndSweepScrubsAllCopiesKeepingPublishedAnswer() {
        long account = account();
        var first = note(account, "note", "private first", 0L);
        String source = first.source().sourceId();
        String run = linkedRun(account, first, "SUCCEEDED");
        note(account, "note", "private second", 1L);
        jdbc.update("INSERT INTO ma_note_version(source_id,version,title) "
                + "VALUES (?,1,'old private title'),(?,2,'new private title')", source, source);
        String anchor = jdbc.queryForObject("SELECT anchor_id FROM ma_anchor WHERE source_id=? AND version=1",
                String.class, source);
        insertChunk(account, source, anchor, "private chunk", "retention-fixture");
        jdbc.update("INSERT INTO ma_index_job(event_id,status,fence,lease_until) "
                + "SELECT event_id,'PROCESSING',1,CURRENT_TIMESTAMP FROM ma_outbox "
                + "WHERE aggregate_type='SOURCE' AND aggregate_id=?", source);
        sources.withdraw(account, source);
        assertThatThrownBy(() -> sources.get(account, source, 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sources.get(account, source, 2)).isInstanceOf(RuntimeException.class);
        assertThat(sources.markIndex(account, source, 2, "READY")).isFalse();
        assertThatThrownBy(() -> insertChunk(account, source, anchor, "late private copy", "retention-fixture-late"))
                .isInstanceOf(RuntimeException.class).hasStackTraceContaining("source version is unavailable");
        retention.sweep(100);
        assertThat(jdbc.queryForObject("SELECT title FROM ma_source WHERE source_id=?", String.class, source)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_source_version WHERE source_id=? "
                + "AND body_markdown IS NULL AND purged_at IS NOT NULL", Integer.class, source)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_anchor WHERE text IS NOT NULL OR locator_json<>'{}'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_note_version WHERE title<>''", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_retrieval_chunk", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_index_job WHERE status<>'DONE'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_evidence WHERE claim<>''", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_attempt WHERE output IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT body_markdown FROM ma_result WHERE run_id=?", String.class, run))
                .isEqualTo("独立教学答复 [1]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_citation", Integer.class)).isEqualTo(1);
        assertThat(retention.evidence().pendingDeletions()).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE ma_source SET status='ACTIVE' WHERE source_id=?", source))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void thirtyDayRetentionKeepsCurrentRecentAndReferencedHistoryButPurgesUnreferencedOldCopies() {
        long account = account();
        var first = note(account, "history", "referenced", 0L);
        String source = first.source().sourceId();
        linkedRun(account, first, "SUCCEEDED");
        note(account, "history", "old unreferenced", 1L);
        note(account, "history", "recent", 2L);
        note(account, "history", "current", 3L);
        jdbc.update("UPDATE ma_source_version SET created_at=CURRENT_TIMESTAMP - INTERVAL '31 days' WHERE version<=2");
        jdbc.update("UPDATE ma_source_version SET created_at=CURRENT_TIMESTAMP - INTERVAL '29 days' WHERE version=3");
        retention.sweep(100);
        assertThat(sources.get(account, source, 1).version().bodyMarkdown()).isEqualTo("referenced");
        assertThatThrownBy(() -> sources.get(account, source, 2)).isInstanceOf(RuntimeException.class);
        assertThat(sources.get(account, source, 3).version().bodyMarkdown()).isEqualTo("recent");
        assertThat(sources.get(account, source, 4).version().bodyMarkdown()).isEqualTo("current");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_source_version WHERE source_id=?", Integer.class, source)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT purge_reason FROM ma_source_version WHERE source_id=? AND version=2", String.class, source))
                .isEqualTo("OLD_UNREFERENCED");
    }

    @Test
    void activeInternalOutputIsAnExplicitOverdueHoldThenIsScrubbedWhenRunTerminates() {
        long account = account();
        var source = note(account, "running", "private active text", 0L);
        String run = linkedRun(account, source, "RUNNING");
        sources.withdraw(account, source.source().sourceId());
        jdbc.update("UPDATE ma_source_deletion SET deleted_at=CURRENT_TIMESTAMP - INTERVAL '8 days'");
        retention.sweep(100);
        assertThat(retention.evidence().activeRunHolds()).isEqualTo(1);
        assertThat(retention.evidence().overdueDeletions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT output FROM ma_attempt", String.class)).isEqualTo("private copied text");
        jdbc.update("UPDATE ma_run SET status='FAILED' WHERE run_id=?", run);
        retention.sweep(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_attempt WHERE output IS NOT NULL", Integer.class)).isZero();
        assertThat(retention.evidence().pendingDeletions()).isZero();
    }

    private String linkedRun(long account, SourceContent source, String state) {
        long question = jdbc.queryForObject("INSERT INTO question(account_id,request_id,subject,body_markdown,summary) "
                + "VALUES (?,?,'OTHER','test question','test') RETURNING question_id", Long.class, account, id());
        String request = id();
        String run = id();
        String step = id();
        String anchor = id();
        sources.addAnchors(account, source.source().sourceId(), 1, List.of(new Anchor(anchor,
                source.source().sourceId(), 1, "MARKDOWN_BLOCK", 0, source.version().contentSha256(),
                "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,\"startOffset\":0,\"endOffset\":"
                + source.version().bodyMarkdown().length() + "}}", source.version().bodyMarkdown())));
        jdbc.update("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,status,input_deadline_at) "
                + "VALUES (?,?,?,'test','HINT','NEW_CAPTURE','FAILED',CURRENT_TIMESTAMP)", request, account, question);
        jdbc.update("INSERT INTO ma_run(run_id,request_id,account_id,run_no,status,queue_deadline_at,run_deadline_at) "
                + "VALUES (?,?,?,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", run, request, account, state);
        jdbc.update("INSERT INTO ma_step(step_id,run_id,role,sequence,status,input_digest) VALUES (?,?,'TEACH',1,'RUNNING',?)", step, run, "a".repeat(64));
        jdbc.update("INSERT INTO ma_attempt(attempt_id,step_id,attempt_no,status,provider_call_state,fence,output) "
                + "VALUES (?,?,1,'SUCCEEDED','CONFIRMED',1,'private copied text')", id(), step);
        jdbc.update("INSERT INTO ma_evidence(evidence_id,run_id,step_id,type,source_id,version,anchor_id,claim) "
                + "VALUES (?,?,?,'HISTORY',?,1,?,'private claim')", id(), run, step, source.source().sourceId(), anchor);
        String result = id();
        jdbc.update("INSERT INTO ma_result(result_id,run_id,mode,status,body_markdown) VALUES (?,?,'HINT','VERIFIED','独立教学答复 [1]')", result, run);
        jdbc.update("INSERT INTO ma_citation(citation_id,result_id,source_id,version,anchor_id,label,source_type) "
                + "VALUES (?,?,?,1,?,'1','NOTE')", id(), result, source.source().sourceId(), anchor);
        return run;
    }

    private SourceContent note(long account, String origin, String text, long expected) {
        return sources.publish(account, new PublishSource(SourceType.NOTE, origin, "private title", text,
                AnswerManifestValidator.sha256(text.getBytes(StandardCharsets.UTF_8)), null, null, expected, id()));
    }
    private void insertChunk(long account, String source, String anchor, String text, String model) {
        jdbc.update("INSERT INTO ma_retrieval_chunk(chunk_id,account_id,source_id,version,anchor_id,"
                + "question_id,text,method_tags,terms,embedding,embedding_model,dimensions,index_revision) "
                + "VALUES (?,?,?,?,?,NULL,?,CAST('[]' AS jsonb),CAST('[]' AS jsonb),"
                + "CAST('[1,0]' AS vector),?,2,1)", id(), account, source, 1, anchor, text, model);
    }
    private long account() {
        return jdbc.queryForObject("INSERT INTO app_user(username,password_hash) VALUES (?, 'hash') RETURNING account_id", Long.class, "retention_" + id().substring(0, 16));
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("bl-retention-it-");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
