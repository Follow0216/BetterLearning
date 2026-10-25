package com.betterlearning.server.retrieval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.anchor.MarkdownAnchorParser;
import com.betterlearning.server.agent.retrieval.ranking.ChineseMathTerms;
import com.betterlearning.server.agent.retrieval.ranking.HybridRanker;
import com.betterlearning.server.config.MybatisConfiguration;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.config.LearningMapperConfiguration;
import com.betterlearning.server.learning.model.note.NoteModels;
import com.betterlearning.server.learning.service.NoteService;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.service.impl.NoteServiceImpl;
import com.betterlearning.server.learning.service.impl.SourceStoreImpl;
import com.betterlearning.server.learning.validation.NoteContent;
import com.betterlearning.server.learning.validation.SourceAnchorValidator;
import com.betterlearning.server.multiagent.SourceStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;

/** Explicit, bounded real-provider + real-pgvector mechanism smoke; never a JUnit test. */
public final class RealEmbeddingPgvectorSmoke {
    private static final int MAX_REQUESTS = 8;
    private static final int MAX_INPUTS = 20;
    private static final String QUERY = "我怎样用配方法把二次函数写成顶点式？";
    private static final String CORRECT = "配方法处理二次函数，整理为完全平方再读取顶点。";
    private static final String WRONG_METHOD = "换元法处理根式方程，回代检查定义域。";
    private static final String FOREIGN = "配方法整理二次函数顶点式。";
    private static final String DELETED = "配方法整理二次函数的旧笔记。";
    private static final String STALE = "配方法旧版二次函数步骤。";
    private static final DockerImageName IMAGE = DockerImageName.parse(
            "pgvector/pgvector@sha256:42e7f6b4e1eceb02ff14e3e6bc6108bbe259abbe83879dc1845d0da1ddeb555d")
            .asCompatibleSubstituteFor("postgres");
    private static final ObjectMapper OUTPUT = new ObjectMapper();

    private RealEmbeddingPgvectorSmoke() {
    }

    @Configuration(proxyBeanMethods = false)
    @MapperScan("com.betterlearning.server.retrieval.mapper")
    @EnableTransactionManagement
    static class MapperConfiguration {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: RealEmbeddingPgvectorSmoke <output-dir>");
        }
        EmbeddingProperties properties = propertiesFromEnvironment();
        // Every one-paragraph note must produce exactly one input. This preflight precedes Docker/network.
        for (String body : List.of(CORRECT, WRONG_METHOD, FOREIGN, DELETED, STALE)) {
            require(MarkdownAnchorParser.parse(body).size() == 1, "fixture must have one anchor per note");
        }
        require(5 + 1 <= MAX_INPUTS && 5 + 1 <= MAX_REQUESTS, "embedding budget exceeded");
        Path outputDir = Path.of(args[0]).toAbsolutePath();
        PostgreSQLContainer postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("betterlearning").withUsername("test").withPassword("test");
        AnnotationConfigApplicationContext context = null;
        try {
            postgres.start();
            DataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            JsonMapper json = JsonMapper.builder().build();
            AnswerFileStore files = mock(AnswerFileStore.class); // This bounded fixture contains only notes.
            context = new AnnotationConfigApplicationContext();
            String secret = Base64.getEncoder().encodeToString(
                    "integration-test-signing-key-32-bytes".getBytes(StandardCharsets.UTF_8));
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("smokeCursorKey", Map.of("app.jwt.secret-base64", secret)));
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(dataSource));
            context.registerBean(JsonMapper.class, () -> json);
            context.registerBean(AnswerFileStore.class, () -> files);
            context.register(MybatisConfiguration.class, LearningMapperConfiguration.class,
                    MapperConfiguration.class, MybatisPlusAutoConfiguration.class,
                    SourceAnchorValidator.class, SourceStoreImpl.class, NoteServiceImpl.class,
                    MybatisRetrievalIndexStore.class, CitationService.class);
            context.refresh();
            run(outputDir, properties, jdbc, json, files,
                    context.getBean(NoteService.class), context.getBean(SourceStore.class),
                    context.getBean(RetrievalIndexStore.class), context.getBean(CitationService.class));
        } finally {
            if (context != null) {
                context.close();
            }
            postgres.stop();
        }
    }

    private static void run(Path outputDir, EmbeddingProperties properties, JdbcTemplate jdbc,
            JsonMapper json, AnswerFileStore files, NoteService notes, SourceStore sources,
            RetrievalIndexStore index, CitationService citations) throws Exception {
        long owner = account(jdbc, "smoke-owner");
        long stranger = account(jdbc, "smoke-foreign");
        HttpEmbeddingClient embedding = new HttpEmbeddingClient(properties);
        RetrievalIndexWorker worker = new RetrievalIndexWorker(sources, index, embedding,
                properties, json, new HandwritingSourceReader(files, null, json));
        List<Map<String, Object>> indexed = new ArrayList<>();
        NoteModels.Note correct = createAndIndex(notes, index, worker, owner, CORRECT, indexed, "correct");
        NoteModels.Note wrong = createAndIndex(notes, index, worker, owner, WRONG_METHOD, indexed, "wrong-method");
        NoteModels.Note foreign = createAndIndex(notes, index, worker, stranger, FOREIGN, indexed, "foreign");
        NoteModels.Note deleted = createAndIndex(notes, index, worker, owner, DELETED, indexed, "deleted");
        NoteModels.Note stale = createAndIndex(notes, index, worker, owner, STALE, indexed, "stale");

        String anchorId = sources.get(owner, correct.sourceId(), 1).anchors().get(0).anchorId();
        String citationId = publishedCitation(jdbc, owner, correct.sourceId(), anchorId);
        require(CORRECT.equals(citations.resolve(owner, citationId).markdown()),
                "published citation must resolve original text");
        notes.delete(owner, deleted.noteId(), NoteContent.etag(deleted.noteId(), deleted.version()));
        NoteModels.Note staleV2 = notes.update(owner, stale.noteId(),
                NoteContent.etag(stale.noteId(), stale.version()),
                new NoteModels.Write("新版换元笔记", "换元法新版草稿，等待索引。"));
        require(staleV2.version() == 2 && "INDEX_PENDING".equals(staleV2.indexStatus()),
                "updated note must be pending");
        require(jdbc.queryForObject("SELECT COUNT(*) FROM ma_retrieval_chunk WHERE source_id IN (?,?,?)",
                Integer.class, deleted.sourceId(), stale.sourceId(), correct.sourceId()) == 3,
                "old chunks must remain physically present for authorization check");

        Instant queryStarted = Instant.now();
        HttpEmbeddingClient.EmbeddingResult query = embedding.embedWithStats(List.of(QUERY));
        long queryMillis = Duration.between(queryStarted, Instant.now()).toMillis();
        require(query.vectors().size() == 1 && query.requestCount() == 1,
                "query embedding response count mismatch");
        List<HybridRanker.Candidate> candidates = index.candidates(owner,
                ChineseMathTerms.terms(QUERY), Set.of("配方法"), query.vectors().get(0),
                properties.getModel(), properties.getDimensions());
        HybridRanker.Ranking ranking = new HybridRanker().rank(
                new HybridRanker.Query(QUERY, Set.of("配方法")), candidates,
                HybridRanker.Limits.defaults(), null);
        Set<String> candidateIds = candidates.stream().map(HybridRanker.Candidate::sourceId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> rankedIds = ranking.evidence().stream().map(item -> item.candidate().sourceId())
                .collect(java.util.stream.Collectors.toSet());
        require(candidateIds.contains(correct.sourceId()), "correct note absent from SQL candidates");
        for (String excluded : List.of(foreign.sourceId(), deleted.sourceId(), stale.sourceId())) {
            require(!candidateIds.contains(excluded), "unauthorized/stale note in SQL candidates");
        }
        require(rankedIds.contains(correct.sourceId()) && !rankedIds.contains(wrong.sourceId()),
                "hybrid method gate failed");

        NoteModels.Note correctV2 = notes.update(owner, correct.noteId(),
                NoteContent.etag(correct.noteId(), correct.version()),
                new NoteModels.Write("配方法修订", "配方法新版本等待索引。"));
        require(correctV2.version() == 2 && CORRECT.equals(citations.resolve(owner, citationId).markdown()),
                "citation must remain pinned to version one after edit");
        notes.delete(owner, correct.noteId(), NoteContent.etag(correct.noteId(), correctV2.version()));
        boolean revoked = false;
        try {
            citations.resolve(owner, citationId);
        } catch (BusinessException exception) {
            revoked = exception.getErrorCode() == ErrorCode.NOT_FOUND;
        }
        require(revoked, "deleted citation must resolve as 404");

        Map<String, Object> usage = jdbc.queryForMap("SELECT COALESCE(SUM(embedding_requests),0) AS requests, "
                + "SUM(embedding_tokens) AS tokens, BOOL_OR(usage_uncertain) AS uncertain "
                + "FROM ma_index_job WHERE status = 'DONE'");
        long workerRequests = ((Number) usage.get("requests")).longValue();
        require(workerRequests == 5 && workerRequests + query.requestCount() <= MAX_REQUESTS,
                "embedding request budget exceeded");
        int inputCount = 5 + query.vectors().size();
        require(inputCount <= MAX_INPUTS, "embedding input budget exceeded");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "real-embedding-pgvector-mechanism-smoke");
        metadata.put("qualityGateEligible", false);
        metadata.put("reviewStatus", "DEVELOPMENT_ONLY");
        metadata.put("configuredModel", properties.getModel());
        metadata.put("responseModel", query.responseModel());
        metadata.put("dimensions", properties.getDimensions());
        metadata.put("embeddingRequests", workerRequests + query.requestCount());
        metadata.put("embeddingInputs", inputCount);
        metadata.put("maxRequests", MAX_REQUESTS);
        metadata.put("maxInputs", MAX_INPUTS);
        metadata.put("workerTokens", usage.get("tokens"));
        metadata.put("workerUsageUncertain", usage.get("uncertain"));
        metadata.put("queryPromptTokens", query.promptTokens());
        metadata.put("queryTotalTokens", query.totalTokens());
        metadata.put("queryLatencyMillis", queryMillis);
        metadata.put("indexed", indexed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", QUERY);
        result.put("queryMethodTags", List.of("配方法"));
        result.put("sqlCandidates", candidates.stream().map(item -> Map.of(
                "sourceId", item.sourceId(), "version", item.version(),
                "anchorId", item.anchorId(), "text", item.text())).toList());
        result.put("ranked", ranking.evidence().stream().map(item -> Map.of(
                "sourceId", item.candidate().sourceId(), "text", item.excerpt(),
                "matchedMethods", item.matchedMethods())).toList());
        result.put("filters", Map.of("foreignExcluded", !candidateIds.contains(foreign.sourceId()),
                "deletedExcluded", !candidateIds.contains(deleted.sourceId()),
                "staleVersionExcluded", !candidateIds.contains(stale.sourceId()),
                "wrongMethodExcludedByRanker", !rankedIds.contains(wrong.sourceId())));
        result.put("citation", Map.of("publishedAnchorId", anchorId,
                "pinnedOriginalText", CORRECT, "afterEditStillOriginal", true,
                "afterDeleteErrorCode", "A0606", "expectedHttpStatus", 404, "assertionLayer", "service"));
        Files.createDirectories(outputDir);
        OUTPUT.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("metadata.json").toFile(), metadata);
        OUTPUT.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("result.json").toFile(), result);
        System.out.println("Real embedding + pgvector smoke passed; output: " + outputDir);
    }

    private static NoteModels.Note createAndIndex(NoteService notes, RetrievalIndexStore index,
            RetrievalIndexWorker worker, long accountId, String body,
            List<Map<String, Object>> indexed, String role) {
        NoteModels.Note note = notes.create(accountId, UUID.randomUUID().toString(),
                new NoteModels.Write(role, body));
        RetrievalIndexStore.Job job = index.claim().orElseThrow();
        require(job.accountId() == accountId && job.sourceId().equals(note.sourceId()),
                "claimed unexpected index job");
        Instant started = Instant.now();
        worker.process(job);
        long latency = Duration.between(started, Instant.now()).toMillis();
        require("READY".equals(notes.get(accountId, note.noteId()).indexStatus()),
                "worker failed to index " + role);
        indexed.add(Map.of("role", role, "sourceId", note.sourceId(),
                "version", note.version(), "latencyMillis", latency));
        return note;
    }

    private static long account(JdbcTemplate jdbc, String label) {
        Long accountId = jdbc.queryForObject("INSERT INTO app_user(username,password_hash,role) "
                + "VALUES (?,?,?) RETURNING account_id", Long.class,
                label + UUID.randomUUID().toString().substring(0, 8), "test-hash", "USER");
        return java.util.Objects.requireNonNull(accountId);
    }

    private static String publishedCitation(JdbcTemplate jdbc, long accountId,
            String sourceId, String anchorId) {
        Long questionId = jdbc.queryForObject("INSERT INTO question(account_id,request_id,subject,body_markdown,summary) "
                + "VALUES (?,?,?,?,?) RETURNING question_id", Long.class, accountId,
                UUID.randomUUID().toString(), "OTHER", "公开自造测试题", "公开自造测试题");
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
                "HISTORY", sourceId, 1, anchorId, claim);
        jdbc.update("INSERT INTO ma_result(result_id,run_id,mode,status,body_markdown) VALUES (?,?,?,?,?)",
                resultId, runId, "HINT", "VERIFIED", "见引用");
        jdbc.update("INSERT INTO ma_citation(citation_id,result_id,source_id,version,anchor_id,label,source_type) "
                + "VALUES (?,?,?,?,?,?,?)", citationId, resultId, sourceId, 1, anchorId, "引用 1", "NOTE");
        return citationId;
    }

    private static EmbeddingProperties propertiesFromEnvironment() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(requiredEnv("APP_AGENT_EMBEDDING_BASE_URL"));
        properties.setApiKey(requiredEnv("APP_AGENT_EMBEDDING_API_KEY"));
        properties.setModel(requiredEnv("APP_AGENT_EMBEDDING_MODEL"));
        properties.setDimensions(Integer.parseInt(requiredEnv("APP_AGENT_EMBEDDING_DIMENSIONS")));
        properties.setBatchSize(Integer.parseInt(System.getenv().getOrDefault(
                "APP_AGENT_EMBEDDING_BATCH_SIZE", "10")));
        properties.setTimeoutSeconds(Integer.parseInt(System.getenv().getOrDefault(
                "APP_AGENT_EMBEDDING_TIMEOUT_SECONDS", "60")));
        properties.validate();
        return properties;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required environment variable: " + name);
        }
        return value;
    }

    private static void require(boolean valid, String reason) {
        if (!valid) {
            throw new IllegalStateException(reason);
        }
    }
}
