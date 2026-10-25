package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Chunk;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Job;
import com.betterlearning.server.agent.retrieval.anchor.MarkdownAnchorParser;
import com.betterlearning.server.agent.retrieval.ranking.ChineseMathTerms;
import com.betterlearning.server.agent.retrieval.ranking.HybridRanker;
import com.betterlearning.server.config.MybatisConfiguration;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.ApiErrorResponseFactory;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.config.LearningMapperConfiguration;
import com.betterlearning.server.learning.controller.NoteControllerAdvice;
import com.betterlearning.server.learning.mapper.RetentionMapper;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.model.note.NoteModels;
import com.betterlearning.server.learning.retention.DeletionJournal;
import com.betterlearning.server.learning.retention.SourceDeletionService;
import com.betterlearning.server.learning.service.NoteService;
import com.betterlearning.server.learning.service.NoteService.NoteFailure;
import com.betterlearning.server.learning.service.impl.NoteServiceImpl;
import com.betterlearning.server.learning.service.impl.SourceStoreImpl;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.validation.NoteContent;
import com.betterlearning.server.learning.validation.SourceAnchorValidator;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.PublishSource;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.SourceStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

/** 真实 PostgreSQL/pgvector 机制验证；二维合成向量不代表中文语义质量。 */
@Testcontainers
@Tag("integration")
class PersonalRetrievalIntegrationTests {
    private static final String MODEL = "synthetic-mechanism-only";
    private static final DockerImageName IMAGE = DockerImageName.parse(
            "pgvector/pgvector@sha256:42e7f6b4e1eceb02ff14e3e6bc6108bbe259abbe83879dc1845d0da1ddeb555d")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("betterlearning").withUsername("test").withPassword("test");

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static NoteService notes;
    private static SourceStore sources;
    private static RetrievalIndexStore index;
    private static CitationService citations;
    private static AnswerFileStore files;
    private static Path journalRoot;
    private long owner;
    private long stranger;

    @Configuration(proxyBeanMethods = false)
    @MapperScan("com.betterlearning.server.retrieval.mapper")
    @EnableTransactionManagement
    static class RetrievalTestConfiguration {
        @Bean(initMethod = "recoverBeforeServing")
        SourceDeletionService sourceDeletionService(RetentionMapper retention,
                PlatformTransactionManager transactionManager) throws IOException {
            return new SourceDeletionService(DeletionJournal.initialize(journalRoot), retention,
                    new TransactionTemplate(transactionManager));
        }
    }

    @BeforeAll
    static void setupDatabase() throws IOException {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        journalRoot = Files.createTempDirectory("bl-personal-retrieval-journal-");
        context = new AnnotationConfigApplicationContext();
        String jwtSecret = Base64.getEncoder().encodeToString(
                "integration-test-signing-key-32-bytes".getBytes(StandardCharsets.UTF_8));
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testCursorKey", Map.of("app.jwt.secret-base64", jwtSecret,
                "app.agent-retention.sweep-enabled", "false")));
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        files = mock(AnswerFileStore.class);
        context.registerBean(AnswerFileStore.class, () -> files);
        context.register(MybatisConfiguration.class, LearningMapperConfiguration.class,
                RetrievalTestConfiguration.class, MybatisPlusAutoConfiguration.class,
                SourceAnchorValidator.class, SourceStoreImpl.class, NoteServiceImpl.class,
                MybatisRetrievalIndexStore.class, CitationService.class);
        context.refresh();
        notes = context.getBean(NoteService.class);
        sources = context.getBean(SourceStore.class);
        index = context.getBean(RetrievalIndexStore.class);
        citations = context.getBean(CitationService.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE TABLE app_user RESTART IDENTITY CASCADE");
        owner = account("owner");
        stranger = account("stranger");
    }

    @Test
    void noteIdempotencyConcurrentCasAndOwnerIsolation() throws Exception {
        String key = UUID.randomUUID().toString();
        NoteModels.Note created = notes.create(owner, key, new NoteModels.Write("标题", "旧正文"));
        assertThat(notes.create(owner, key, new NoteModels.Write("标题", "旧正文")).noteId())
                .isEqualTo(created.noteId());
        assertThatThrownBy(() -> notes.create(owner, key, new NoteModels.Write("不同标题", "旧正文")))
                .isInstanceOfSatisfying(NoteFailure.class, error -> {
                    assertThat(error.failure()).isEqualTo(NoteService.Failure.CONFLICT);
                    assertThat(httpStatus(error)).isEqualTo(409);
                });
        assertThatThrownBy(() -> notes.get(stranger, created.noteId()))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        String etag = NoteContent.etag(created.noteId(), created.version());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> first = updateAttempt(created.noteId(), etag, "第一版", ready, start);
            Callable<Object> second = updateAttempt(created.noteId(), etag, "第二版", ready, start);
            Future<Object> a = workers.submit(first);
            Future<Object> b = workers.submit(second);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(NoteModels.Note.class::isInstance).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(NoteFailure.class::isInstance).count()).isEqualTo(1);
            NoteFailure failure = (NoteFailure) outcomes.stream()
                    .filter(NoteFailure.class::isInstance).findFirst().orElseThrow();
            assertThat(failure.failure()).isEqualTo(NoteService.Failure.PRECONDITION);
            assertThat(httpStatus(failure)).isEqualTo(412);
        } finally {
            workers.shutdownNow();
        }
        assertThat(notes.get(owner, created.noteId()).version()).isEqualTo(2);
    }

    @Test
    void currentReadyOnlyAndLateOldOutboxCannotRestoreOldVersion() {
        NoteModels.Note note = notes.create(owner, UUID.randomUUID().toString(),
                new NoteModels.Write("方法", "旧方法"));
        Job old = index.claim().orElseThrow();
        NoteModels.Note edited = notes.update(owner, note.noteId(), NoteContent.etag(note.noteId(), 1),
                new NoteModels.Write("方法", "新方法"));
        assertThat(index.complete(old, List.of(chunk(note.sourceId(), 1, "旧方法", 1, 0)), MODEL, 2))
                .isFalse();
        assertThat(index.candidates(owner, Set.of("旧方法"), Set.of(), new double[] {1, 0}, MODEL, 2))
                .isEmpty();
        Job current = index.claim().orElseThrow();
        assertThat(current.version()).isEqualTo(2);
        assertThat(index.complete(current, List.of(chunk(note.sourceId(), 2, "新方法", 0, 1)), MODEL, 2))
                .isTrue();
        assertThat(index.candidates(owner, Set.of("新方法"), Set.of(), new double[] {0, 1}, MODEL, 2))
                .extracting(candidate -> candidate.version()).containsOnly(2L);
        assertThat(index.candidates(stranger, Set.of("新方法"), Set.of(), new double[] {0, 1}, MODEL, 2))
                .isEmpty();
        notes.delete(owner, note.noteId(), NoteContent.etag(note.noteId(), edited.version()));
        assertThat(index.candidates(owner, Set.of("新方法"), Set.of(), new double[] {0, 1}, MODEL, 2))
                .isEmpty();
    }

    @Test
    void expiredExternalIntentCannotBeReplayedButExplicitReindexCanStartANewAttempt() {
        NoteModels.Note note = notes.create(owner, UUID.randomUUID().toString(),
                new NoteModels.Write("调用故障", "公开配方法笔记"));
        Job first = index.claim().orElseThrow();
        assertThat(index.beginExternalCall(first)).isTrue();
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_index_job WHERE event_id = ?",
                String.class, first.eventId())).isEqualTo("CALL_INTENT");
        // 模拟外呼之后进程消失，既不确认也不主动失败；持久租约过期。
        jdbc.update("UPDATE ma_index_job SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                + "WHERE event_id = ?", first.eventId());
        assertThat(index.claim()).isEmpty();
        assertThat(index.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM ma_index_job WHERE event_id = ?",
                String.class, first.eventId())).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT usage_uncertain FROM ma_index_job WHERE event_id = ?",
                Boolean.class, first.eventId())).isTrue();
        assertThat(sources.get(owner, note.sourceId(), 1).version().status()).isEqualTo("FAILED");
        assertThat(index.beginExternalCall(first)).isFalse();
        assertThat(index.confirmExternalCall(first)).isFalse();
        assertThat(sources.reindex(owner, note.sourceId(), 1, 1).indexRevision()).isEqualTo(2);
        Job explicit = index.claim().orElseThrow();
        assertThat(explicit.indexRevision()).isEqualTo(2);
        assertThat(explicit.eventId()).isNotEqualTo(first.eventId());
        // 新 revision 尚未发送外呼，租约过期仍可重领同一 job。
        jdbc.update("UPDATE ma_index_job SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                + "WHERE event_id = ?", explicit.eventId());
        Job reclaimed = index.claim().orElseThrow();
        assertThat(reclaimed.eventId()).isEqualTo(explicit.eventId());
        assertThat(reclaimed.fence()).isGreaterThan(explicit.fence());
        assertThat(index.beginExternalCall(explicit)).isFalse();
        assertThat(index.beginExternalCall(reclaimed)).isTrue();
        assertThat(index.confirmExternalCall(reclaimed)).isTrue();
        // 确认响应但向量尚未原子发布时同样不自动重做付费调用。
        jdbc.update("UPDATE ma_index_job SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                + "WHERE event_id = ?", reclaimed.eventId());
        assertThat(index.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM ma_index_job WHERE event_id = ?",
                String.class, reclaimed.eventId())).isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    void failedJobsDoNotStarvePreparationOfNewSourceEvents() {
        for (int ordinal = 0; ordinal < 100; ordinal++) {
            String eventId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,"
                    + "aggregate_version,kind,payload_digest,index_revision,status,created_at) "
                    + "VALUES (?,?,'SOURCE',?,1,'SOURCE_VERSION_CREATED',?,1,'FAILED',"
                    + "CURRENT_TIMESTAMP - INTERVAL '1 day')", eventId, owner,
                    UUID.randomUUID().toString(), "a".repeat(64));
            jdbc.update("INSERT INTO ma_index_job(event_id,status) VALUES (?,'FAILED')", eventId);
        }
        NoteModels.Note note = notes.create(owner, UUID.randomUUID().toString(),
                new NoteModels.Write("后续笔记", "新事件必须能消费"));
        Job job = index.claim().orElseThrow();
        assertThat(job.sourceId()).isEqualTo(note.sourceId());
    }

    @Test
    void readyOldVersionDoesNotFallBackWhileNewVersionIsPending() {
        NoteModels.Note note = notes.create(owner, UUID.randomUUID().toString(),
                new NoteModels.Write("方法", "旧内容"));
        Job old = index.claim().orElseThrow();
        assertThat(index.complete(old, List.of(chunk(note.sourceId(), 1, "旧内容", 1, 0)), MODEL, 2))
                .isTrue();
        assertThat(index.candidates(owner, Set.of("旧内容"), Set.of(), new double[] {1, 0}, MODEL, 2))
                .hasSize(1);
        notes.update(owner, note.noteId(), NoteContent.etag(note.noteId(), 1),
                new NoteModels.Write("方法", "新内容"));
        assertThat(index.candidates(owner, Set.of("旧内容"), Set.of(), new double[] {1, 0}, MODEL, 2))
                .isEmpty();
    }

    @Test
    void readTimeMethodAliasRecallsLegacyEmptyMetadataBeyondTopFortyVectors() {
        for (int ordinal = 0; ordinal < 41; ordinal++) {
            String text = "链式法则用于复合函数求导，第" + ordinal + "份旧资料";
            NoteModels.Note distractor = notes.create(owner, UUID.randomUUID().toString(),
                    new NoteModels.Write("高向量错方法", text));
            Job job = index.claim().orElseThrow();
            Chunk draft = chunk(distractor.sourceId(), 1, text, 1, 0);
            Chunk legacy = new Chunk(draft.anchor(), null, draft.text(), Set.of(), Set.of(),
                    new double[] {1, 0});
            assertThat(index.complete(job, List.of(legacy), MODEL, 2)).isTrue();
        }
        String correctText = "平方差公式：a²−b²=(a+b)(a−b)，可据此分解";
        NoteModels.Note correct = notes.create(owner, UUID.randomUUID().toString(),
                new NoteModels.Write("旧平方差资料", correctText));
        Job correctJob = index.claim().orElseThrow();
        Chunk draft = chunk(correct.sourceId(), 1, correctText, 0.99, 0.15);
        Chunk legacyCorrect = new Chunk(draft.anchor(), null, draft.text(), Set.of(), Set.of(),
                new double[] {0.99, 0.15});
        assertThat(index.complete(correctJob, List.of(legacyCorrect), MODEL, 2)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ma_retrieval_chunk WHERE account_id=? "
                + "AND method_tags='[]'::jsonb AND terms='[]'::jsonb", Integer.class, owner))
                .isEqualTo(42);

        Set<String> methods = Set.of("平方差公式");
        var candidates = index.candidates(owner, ChineseMathTerms.terms("平方差公式"), methods,
                new double[] {1, 0}, MODEL, 2);
        assertThat(candidates).extracting(candidate -> candidate.sourceId())
                .contains(correct.sourceId());
        assertThat(candidates.stream().filter(candidate -> candidate.sourceId().equals(correct.sourceId()))
                .findFirst().orElseThrow().lexicalScore()).isPositive();

        var ranked = new HybridRanker().rank(new HybridRanker.Query("使用平方差公式分解 a²−b²", methods),
                candidates, HybridRanker.Limits.defaults(), null);
        assertThat(ranked.evidence()).hasSize(1);
        assertThat(ranked.evidence().get(0).candidate().sourceId()).isEqualTo(correct.sourceId());
        assertThat(ranked.evidence().get(0).candidate().text()).isEqualTo(correctText);
    }

    @Test
    void citationResolvesPinnedOldTextThenDeletionAndWithheldResultRevokeIt() {
        NoteModels.Note note = notes.create(owner, UUID.randomUUID().toString(),
                new NoteModels.Write("旧标题", "旧😀内容"));
        Job job = index.claim().orElseThrow();
        Chunk chunk = chunk(note.sourceId(), 1, "旧😀内容", 1, 0);
        assertThat(index.complete(job, List.of(chunk), MODEL, 2)).isTrue();
        String published = citationFixture(owner, note.sourceId(), 1, chunk.anchor().anchorId(), "VERIFIED");
        String withheld = citationFixture(owner, note.sourceId(), 1, chunk.anchor().anchorId(), "WITHHELD");
        assertThat(citations.resolve(owner, published).markdown()).isEqualTo("旧😀内容");
        assertThatThrownBy(() -> citations.resolve(stranger, published))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> citations.resolve(owner, withheld))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        NoteModels.Note updated = notes.update(owner, note.noteId(), NoteContent.etag(note.noteId(), 1),
                new NoteModels.Write("新标题", "新内容"));
        CitationService.Resolution old = citations.resolve(owner, published);
        assertThat(old.markdown()).isEqualTo("旧😀内容");
        assertThat(old.sourceTitle()).isEqualTo("旧标题");
        assertThat(old.currentVersion()).isEqualTo(2);
        notes.delete(owner, note.noteId(), NoteContent.etag(note.noteId(), updated.version()));
        assertThatThrownBy(() -> citations.resolve(owner, published))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void snapshotCitationRequiresCurrentRequestBindingAndCurrentSnapshotEvidence() {
        Long originQuestionId = jdbc.queryForObject("INSERT INTO question(account_id,request_id,subject,"
                + "body_markdown,summary) VALUES (?,?,?,?,?) RETURNING question_id", Long.class,
                owner, UUID.randomUUID().toString(), "OTHER", "原请求题目", "原请求题目");
        String originRequestId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,"
                + "status,input_deadline_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP + INTERVAL '5 minutes')",
                originRequestId, owner, originQuestionId, "提示", "HINT", "NEW_CAPTURE", "SUCCEEDED");
        String fileRef = UUID.randomUUID().toString();
        String digest = "a".repeat(64);
        SourceContent snapshot = sources.publish(owner, new PublishSource(SourceType.TUTOR_SNAPSHOT,
                originRequestId, "本轮快照", null, digest, fileRef, "DRAFT", null,
                "test:snapshot:" + UUID.randomUUID()));
        byte[] png = tinyPng(10, 10);
        AnswerModels.Block raster = new AnswerModels.Block(0, "question_0000", 0.0, 0.0,
                10.0, 10.0, 10, 10, sha256(png));
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout(null, null, null,
                null, null, List.of(), List.of(raster));
        AnswerModels.SaveManifest manifest = new AnswerModels.SaveManifest(1,
                UUID.randomUUID().toString(), null, null,
                new AnswerModels.Document(1, layout, List.of()), List.of());
        when(files.manifest(fileRef)).thenReturn(new AnswerModels.StoredManifest(manifest, null));
        when(files.block(fileRef, "question_0000")).thenReturn(png);
        String sourceId = snapshot.source().sourceId();
        jdbc.update("UPDATE ma_request SET snapshot_file_ref = ?, snapshot_sha256 = ?, "
                + "snapshot_source_id = ?, snapshot_version = 1 WHERE request_id = ?",
                fileRef, digest, sourceId, originRequestId);
        String anchorId = UUID.randomUUID().toString();
        String locator = "{\"schemaVersion\":1,\"handwritingLocator\":{\"segments\":[{"
                + "\"blockIndex\":0,\"questionRaster\":true,"
                + "\"blockWorldBounds\":{\"x\":0,\"y\":0,\"width\":10,\"height\":10},"
                + "\"pixelWidth\":10,\"pixelHeight\":10,"
                + "\"regionWorldBounds\":{\"x\":1,\"y\":1,\"width\":2,\"height\":2}}]}}";
        jdbc.update("INSERT INTO ma_anchor(anchor_id,source_id,version,kind,ordinal,content_sha256,"
                + "locator_json,text) VALUES (?,?,?,?,?,?,?,?)", anchorId, sourceId, 1,
                "QUESTION_BLOCK", 0, "0".repeat(64), locator, null);
        String citationId = citationFixture(owner, sourceId, 1, anchorId, "VERIFIED", "TUTOR_SNAPSHOT");
        String requestId = jdbc.queryForObject("SELECT run.request_id FROM ma_citation c "
                + "JOIN ma_result r ON r.result_id = c.result_id JOIN ma_run run ON run.run_id = r.run_id "
                + "WHERE c.citation_id = ?", String.class, citationId);
        jdbc.update("UPDATE ma_request SET snapshot_kind = 'REUSE_REQUEST', source_request_id = ?, "
                + "snapshot_origin_request_id = ?, snapshot_file_ref = ?, snapshot_sha256 = ?, "
                + "snapshot_source_id = ?, snapshot_version = 1 WHERE request_id = ?",
                originRequestId, originRequestId, fileRef, digest, sourceId, requestId);
        jdbc.update("UPDATE ma_evidence SET type = 'CURRENT_SNAPSHOT' WHERE source_id = ? AND anchor_id = ?",
                sourceId, anchorId);
        assertThat(citations.resolve(owner, citationId).handwritingLocator()).isNotNull();
        assertThat(citations.asset(owner, citationId, 0)).isEqualTo(png);

        String otherFileRef = UUID.randomUUID().toString();
        SourceContent other = sources.publish(owner, new PublishSource(SourceType.TUTOR_SNAPSHOT,
                requestId, "另一快照", null, digest, otherFileRef,
                "DRAFT", null, "test:snapshot:" + UUID.randomUUID()));
        jdbc.update("UPDATE ma_request SET snapshot_kind = 'NEW_CAPTURE', source_request_id = NULL, "
                + "snapshot_origin_request_id = NULL, snapshot_file_ref = ?, snapshot_source_id = ? "
                + "WHERE request_id = ?", otherFileRef, other.source().sourceId(), requestId);
        assertThatThrownBy(() -> citations.resolve(owner, citationId))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND));
        jdbc.update("UPDATE ma_request SET snapshot_kind = 'REUSE_REQUEST', source_request_id = ?, "
                + "snapshot_origin_request_id = ?, snapshot_file_ref = ?, snapshot_source_id = ? "
                + "WHERE request_id = ?", originRequestId, originRequestId, fileRef, sourceId, requestId);
        jdbc.update("UPDATE ma_evidence SET type = 'HISTORY' WHERE source_id = ? AND anchor_id = ?",
                sourceId, anchorId);
        assertThatThrownBy(() -> citations.resolve(owner, citationId))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void deletionSerializesWithInFlightAssetAndLaterReadsAreDenied() throws Exception {
        String fileRef = UUID.randomUUID().toString();
        SourceContent snapshot = sources.publish(owner, new PublishSource(SourceType.SAVED_ANSWER,
                "1", "已保存作答", null, "a".repeat(64), fileRef, "DRAFT", null,
                "test:saved:" + UUID.randomUUID()));
        byte[] png = tinyPng(10, 10);
        AnswerModels.Block block = new AnswerModels.Block(0, "chunk_0000", 0.0, 0.0,
                10.0, 10.0, 10, 10, sha256(png));
        AnswerModels.SaveManifest manifest = new AnswerModels.SaveManifest(1,
                UUID.randomUUID().toString(), null,
                new AnswerModels.Canvas(10.0, 10.0, 0.0, 0.0, "logical"),
                new AnswerModels.Document(1, null, List.of()), List.of(block));
        when(files.manifest(fileRef)).thenReturn(new AnswerModels.StoredManifest(manifest, null));
        String anchorText = "手写步骤";
        String anchorId = UUID.randomUUID().toString();
        String locator = "{\"schemaVersion\":1,\"handwritingLocator\":{\"segments\":[{"
                + "\"blockIndex\":0,\"questionRaster\":false,"
                + "\"blockWorldBounds\":{\"x\":0,\"y\":0,\"width\":10,\"height\":10},"
                + "\"pixelWidth\":10,\"pixelHeight\":10,"
                + "\"regionWorldBounds\":{\"x\":1,\"y\":1,\"width\":2,\"height\":2}}]}}";
        jdbc.update("INSERT INTO ma_anchor(anchor_id,source_id,version,kind,ordinal,content_sha256,"
                + "locator_json,text) VALUES (?,?,?,?,?,?,?,?)", anchorId, snapshot.source().sourceId(),
                1, "HANDWRITING_STEP", 0, sha256(anchorText.getBytes(StandardCharsets.UTF_8)),
                locator, anchorText);
        String citationId = citationFixture(owner, snapshot.source().sourceId(), 1,
                anchorId, "VERIFIED", "SAVED_ANSWER");
        assertThat(citations.resolve(owner, citationId).handwritingLocator()).isNotNull();

        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch deleting = new CountDownLatch(1);
        when(files.block(fileRef, "chunk_0000")).thenAnswer(invocation -> {
            reading.countDown();
            if (!releaseRead.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release asset read");
            }
            return png;
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<byte[]> inFlight = workers.submit(() -> citations.asset(owner, citationId, 0));
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> deletion = workers.submit(() -> {
                deleting.countDown();
                sources.withdraw(owner, snapshot.source().sourceId());
            });
            assertThat(deleting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseRead.countDown();
            assertThat(inFlight.get(10, TimeUnit.SECONDS)).isEqualTo(png);
            deletion.get(10, TimeUnit.SECONDS);
        } finally {
            releaseRead.countDown();
            workers.shutdownNow();
        }
        assertThatThrownBy(() -> citations.resolve(owner, citationId))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> citations.asset(owner, citationId, 0))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void failedRevisionCanReindexExistingAnchorButOldFenceCannotPublish() {
        NoteModels.Note note = notes.create(owner, UUID.randomUUID().toString(),
                new NoteModels.Write("重建", "同一原文"));
        Job first = index.claim().orElseThrow();
        Chunk original = chunk(note.sourceId(), 1, "同一原文", 1, 0);
        sources.addAnchors(owner, note.sourceId(), 1, first.indexRevision(), List.of(original.anchor()));
        index.fail(first, "TEST_FAILURE");
        assertThat(sources.get(owner, note.sourceId(), 1).version().status()).isEqualTo("FAILED");
        assertThat(index.candidates(owner, Set.of("同一原文"), Set.of(), new double[] {1, 0}, MODEL, 2))
                .isEmpty();

        assertThat(sources.reindex(owner, note.sourceId(), 1, 1).indexRevision()).isEqualTo(2);
        Job second = index.claim().orElseThrow();
        assertThat(second.indexRevision()).isEqualTo(2);
        assertThat(second.eventId()).isNotEqualTo(first.eventId());
        jdbc.update("UPDATE ma_index_job SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                + "WHERE event_id = ?", second.eventId());
        Job reclaimed = index.claim().orElseThrow();
        assertThat(reclaimed.eventId()).isEqualTo(second.eventId());
        assertThat(reclaimed.fence()).isGreaterThan(second.fence());
        assertThat(index.complete(first, List.of(original), MODEL, 2)).isFalse();
        assertThat(index.complete(second, List.of(original), MODEL, 2)).isFalse();
        assertThat(index.complete(reclaimed, List.of(original), MODEL, 2)).isTrue();
        assertThat(index.complete(first, List.of(original), MODEL, 2)).isFalse();
        assertThat(sources.get(owner, note.sourceId(), 1).version().status()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_anchor WHERE source_id = ? AND version = 1",
                Long.class, note.sourceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT index_revision FROM ma_retrieval_chunk WHERE source_id = ?",
                Long.class, note.sourceId())).isEqualTo(2);
        assertThat(index.candidates(owner, Set.of("同一原文"), Set.of(), new double[] {1, 0}, MODEL, 2))
                .hasSize(1);
    }

    @Test
    void tutorSnapshotIsExcludedEvenIfAReadyChunkExists() {
        SourceContent snapshot = sources.publish(owner, new PublishSource(SourceType.TUTOR_SNAPSHOT,
                UUID.randomUUID().toString(), "本轮快照", null, "b".repeat(64),
                UUID.randomUUID().toString(), null, null, "test:snapshot:" + UUID.randomUUID()));
        String sourceId = snapshot.source().sourceId();
        String anchorId = UUID.randomUUID().toString();
        String text = "本轮证据";
        String digest = sha256(text.getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO ma_anchor(anchor_id,source_id,version,kind,ordinal,content_sha256,"
                + "locator_json,text) VALUES (?,?,?,?,?,?,?,?)", anchorId, sourceId, 1,
                "MARKDOWN_BLOCK", 0, digest,
                "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,"
                + "\"startOffset\":0,\"endOffset\":" + text.length() + "}}", text);
        assertThat(sources.markIndex(owner, sourceId, 1, "READY")).isTrue();
        jdbc.update("INSERT INTO ma_retrieval_chunk(chunk_id,account_id,source_id,version,anchor_id,"
                + "question_id,text,method_tags,terms,embedding,embedding_model,dimensions,index_revision) "
                + "VALUES (?,?,?,?,?,NULL,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS vector),?,?,?)",
                UUID.randomUUID().toString(), owner, sourceId, 1, anchorId, text,
                "[]", "[\"本轮证据\"]", "[1,0]", MODEL, 2, 1);
        assertThat(index.candidates(owner, Set.of("本轮证据"), Set.of(), new double[] {1, 0}, MODEL, 2))
                .isEmpty();
        assertThat(index.authorized(owner, sourceId, 1, anchorId)).isFalse();
    }

    private Callable<Object> updateAttempt(String noteId, String etag, String title,
            CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                return notes.update(owner, noteId, etag, new NoteModels.Write(title, "更新内容"));
            } catch (NoteFailure failure) {
                return failure;
            }
        };
    }

    private Chunk chunk(String sourceId, long version, String text, double x, double y) {
        MarkdownAnchorParser.Block block = MarkdownAnchorParser.parse(text).get(0);
        String locator = "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,"
                + "\"startOffset\":0,\"endOffset\":" + block.text().length() + "}}";
        Anchor anchor = new Anchor(UUID.randomUUID().toString(), sourceId, version, "MARKDOWN_BLOCK", 0,
                block.contentSha256(), locator, block.text());
        return new Chunk(anchor, null, block.text(), Set.of("换元"), Set.of("方法"), new double[] {x, y});
    }

    private String citationFixture(long accountId, String sourceId, long version,
            String anchorId, String resultStatus) {
        return citationFixture(accountId, sourceId, version, anchorId, resultStatus, "NOTE");
    }

    private String citationFixture(long accountId, String sourceId, long version,
            String anchorId, String resultStatus, String sourceType) {
        Long questionId = jdbc.queryForObject("INSERT INTO question(account_id,request_id,subject,body_markdown,summary) "
                + "VALUES (?,?,?,?,?) RETURNING question_id", Long.class, accountId,
                UUID.randomUUID().toString(), "OTHER", "测试题", "测试题");
        String requestId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        String stepId = UUID.randomUUID().toString();
        String resultId = UUID.randomUUID().toString();
        String citationId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,"
                + "status,input_deadline_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP + INTERVAL '5 minutes')",
                requestId, accountId, questionId, "提示", "HINT", "NEW_CAPTURE", "SUCCEEDED");
        jdbc.update("INSERT INTO ma_run(run_id,request_id,account_id,run_no,status,queue_deadline_at,run_deadline_at) "
                + "VALUES (?,?,?,?,?,CURRENT_TIMESTAMP + INTERVAL '5 minutes',"
                + "CURRENT_TIMESTAMP + INTERVAL '10 minutes')", runId, requestId, accountId, 1, "SUCCEEDED");
        jdbc.update("UPDATE ma_request SET current_run_id = ? WHERE account_id = ? AND request_id = ?",
                runId, accountId, requestId);
        jdbc.update("INSERT INTO ma_step(step_id,run_id,role,sequence,status,input_digest) "
                + "VALUES (?,?,?,?,?,?)", stepId, runId, "RETRIEVE", 1, "SUCCEEDED", "0".repeat(64));
        String claim = jdbc.queryForObject("SELECT COALESCE(text, '') FROM ma_anchor WHERE anchor_id = ?",
                String.class, anchorId);
        jdbc.update("INSERT INTO ma_evidence(evidence_id,run_id,step_id,type,source_id,version,anchor_id,claim) "
                + "VALUES (?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), runId, stepId,
                "HISTORY", sourceId, version, anchorId, claim);
        jdbc.update("INSERT INTO ma_result(result_id,run_id,mode,status,body_markdown) VALUES (?,?,?,?,?)",
                resultId, runId, "HINT", resultStatus, "见引用");
        jdbc.update("INSERT INTO ma_citation(citation_id,result_id,source_id,version,anchor_id,label,source_type) "
                + "VALUES (?,?,?,?,?,?,?)", citationId, resultId, sourceId, version, anchorId, "引用 1", sourceType);
        return citationId;
    }

    private static byte[] tinyPng(int width, int height) {
        byte[] bytes = new byte[24];
        System.arraycopy(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}, 0, bytes, 0, 8);
        System.arraycopy("IHDR".getBytes(StandardCharsets.US_ASCII), 0, bytes, 12, 4);
        ByteBuffer.wrap(bytes, 16, 4).putInt(width);
        ByteBuffer.wrap(bytes, 20, 4).putInt(height);
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private long account(String name) {
        Long id = jdbc.queryForObject("INSERT INTO app_user(username,password_hash,role) "
                + "VALUES (?,?,?) RETURNING account_id", Long.class,
                name + UUID.randomUUID().toString().substring(0, 8), "test-hash", "USER");
        if (id == null) {
            fail("fixture account was not inserted");
        }
        return id;
    }

    private static int httpStatus(NoteFailure failure) {
        return new NoteControllerAdvice(new ApiErrorResponseFactory())
                .handle(failure, new MockHttpServletRequest()).getStatusCode().value();
    }
}
