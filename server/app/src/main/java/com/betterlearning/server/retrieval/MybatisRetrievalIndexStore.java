package com.betterlearning.server.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.betterlearning.server.agent.config.HandwritingMethodsProperties;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingDecision;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingKey;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingResult;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageDecision;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageKey;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageResult;
import com.betterlearning.server.agent.retrieval.handwriting.HandwritingMethodMetadata;
import com.betterlearning.server.agent.retrieval.ranking.ChineseMathTerms;
import com.betterlearning.server.agent.retrieval.ranking.HandwritingMethodTaxonomy;
import com.betterlearning.server.agent.retrieval.ranking.HybridRanker.Candidate;
import com.betterlearning.server.agent.retrieval.ranking.HybridRanker.SourceType;
import com.betterlearning.server.agent.service.HandwritingIndexOcr;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.retrieval.mapper.RetrievalMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/** 只持久化可重建索引；来源和锚点由 P1 SourceStore 保持为事实源。 */
@Repository
public class MybatisRetrievalIndexStore implements RetrievalIndexStore {
    private static final String STRUCTURED_PROTOCOL = "HANDWRITING_METHODS_V1";
    private final RetrievalMapper mapper;
    private final SourceStore sources;
    private final JsonMapper json;
    private final HandwritingMethodsProperties handwritingMethods;

    public MybatisRetrievalIndexStore(RetrievalMapper mapper, SourceStore sources, JsonMapper json) {
        this(mapper, sources, json, new HandwritingMethodsProperties());
    }

    @Autowired
    public MybatisRetrievalIndexStore(RetrievalMapper mapper, SourceStore sources, JsonMapper json,
            ObjectProvider<HandwritingMethodsProperties> provider) {
        this(mapper, sources, json, provider.getIfAvailable(HandwritingMethodsProperties::new));
    }

    private MybatisRetrievalIndexStore(RetrievalMapper mapper, SourceStore sources, JsonMapper json,
            HandwritingMethodsProperties handwritingMethods) {
        this.mapper = mapper;
        this.sources = sources;
        this.json = json;
        this.handwritingMethods = handwritingMethods;
    }

    @Override
    @Transactional
    public Optional<Job> claim() {
        mapper.prepareJobs();
        Map<String, Object> row = mapper.claim();
        if (row == null) {
            return Optional.empty();
        }
        Job job = new Job(text(row, "event_id"), number(row, "account_id"),
                text(row, "aggregate_id"), number(row, "aggregate_version"), text(row, "kind"),
                number(row, "fence"), number(row, "index_revision"), (int) number(row, "attempts"));
        String protocol = text(row, "index_protocol");
        if (!"NOT_SENT".equals(text(row, "call_state"))
                || protocol != null && (!STRUCTURED_PROTOCOL.equals(protocol)
                || mapper.unconfirmedStageCount(job.eventId()) != 0)) {
            // 租约超时只证明 worker 失联，无法证明外部供应商没有计费或返回结果。
            if (STRUCTURED_PROTOCOL.equals(protocol)) {
                mapper.unknownUnconfirmedStages(job.eventId());
            }
            fail(job, "OUTCOME_UNKNOWN");
            return Optional.empty();
        }
        return Optional.of(job);
    }

    @Override
    @Transactional
    public boolean complete(Job job, List<Chunk> chunks, String model, int dimensions) {
        if (mapper.lockAccount(job.accountId()) == null
                || !job.eventId().equals(mapper.lockJob(job.eventId(), job.fence()))) {
            return false;
        }
        var content = sources.get(job.accountId(), job.sourceId(), job.version());
        if (!"ACTIVE".equals(content.source().status())
                || content.source().currentVersion() != job.version()
                || content.version().indexRevision() != job.indexRevision()) {
            obsolete(job);
            return false;
        }
        if ("READY".equals(content.version().status())) {
            acknowledge(job);
            return true;
        }
        if (!"INDEX_PENDING".equals(content.version().status())) {
            obsolete(job);
            return false;
        }
        if (mapper.currentIndexEvent(job.eventId(), job.accountId(), job.sourceId(),
                job.version(), job.indexRevision()) != 1) {
            obsolete(job);
            return false;
        }
        if (STRUCTURED_PROTOCOL.equals(mapper.indexProtocol(job.eventId(), job.fence()))) {
            if (mapper.unconfirmedStageCount(job.eventId()) != 0
                    || mapper.invalidIndexStageBindings(job.eventId(), model, dimensions) != 0
                    || mapper.confirmedOcrStageCount(job.eventId()) != chunks.size()
                    || chunks.stream().anyMatch(chunk -> chunk.methodMetadataJson() == null)) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
            for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
                if (chunks.get(ordinal).anchor().ordinal() != ordinal) {
                    throw new StepExecutionException("OUTCOME_UNKNOWN");
                }
            }
            verifyEmbeddingStages(job, content, chunks, model, dimensions);
        }
        // 显式 reindex 可复用已提交的稳定锚点，避免重新 OCR。
        var existing = content.anchors();
        if (existing.isEmpty()) {
            sources.addAnchors(job.accountId(), job.sourceId(), job.version(), job.indexRevision(),
                    chunks.stream().map(Chunk::anchor).toList());
        } else if (existing.size() != chunks.size() || chunks.stream().anyMatch(chunk -> existing.stream()
                .noneMatch(anchor -> anchor.anchorId().equals(chunk.anchor().anchorId())
                && anchor.contentSha256().equals(chunk.anchor().contentSha256())
                && java.util.Objects.equals(anchor.text(), chunk.anchor().text())
                && anchor.locatorJson().equals(chunk.anchor().locatorJson())))) {
            throw new IllegalStateException("Immutable anchor mismatch");
        }
        mapper.deleteChunks(job.accountId(), job.sourceId(), job.version());
        for (Chunk chunk : chunks) {
            if (chunk.anchor() == null || !job.sourceId().equals(chunk.anchor().sourceId())
                    || job.version() != chunk.anchor().version() || chunk.text() == null
                    || !chunk.text().equals(chunk.anchor().text())
                    || !sha256(chunk.text()).equals(chunk.anchor().contentSha256())) {
                throw new IllegalArgumentException("Index chunk does not match anchor text");
            }
            validateChunkMetadata(job, content, chunk);
            Map<String, Object> row = new HashMap<>();
            row.put("id", UUID.randomUUID().toString());
            row.put("accountId", job.accountId());
            row.put("sourceId", job.sourceId());
            row.put("version", job.version());
            row.put("indexRevision", job.indexRevision());
            row.put("anchorId", chunk.anchor().anchorId());
            row.put("questionId", chunk.questionId());
            row.put("text", chunk.text());
            row.put("methodTags", json.writeValueAsString(chunk.methodMetadataJson() == null
                    ? chunk.methodTags() : ChineseMathTerms.methodsInText(chunk.text())));
            row.put("terms", json.writeValueAsString(chunk.terms()));
            row.put("vector", vector(chunk.embedding(), dimensions));
            row.put("model", model);
            row.put("dimensions", dimensions);
            row.put("methodMetadata", chunk.methodMetadataJson());
            row.put("inferredMethodTags", json.writeValueAsString(chunk.methodMetadataJson() == null
                    ? Set.of() : HandwritingMethodMetadata.parse(chunk.methodMetadataJson()).eligibleMethods()));
            mapper.insertChunk(row);
        }
        if (!sources.markIndex(job.accountId(), job.sourceId(), job.version(), job.indexRevision(), "READY")) {
            throw new IllegalStateException("Index compare-and-set failed");
        }
        acknowledge(job);
        return true;
    }

    @Override
    @Transactional
    public void fail(Job job, String reason) {
        if (mapper.lockAccount(job.accountId()) != null
                && job.eventId().equals(mapper.lockJob(job.eventId(), job.fence()))) {
            if (mapper.failure(job.eventId(), job.fence(), reason) != 1) {
                throw new IllegalStateException("Index failure fence lost");
            }
            sources.markIndex(job.accountId(), job.sourceId(), job.version(), job.indexRevision(), "FAILED");
            mapper.eventStatus(job.eventId(), job.accountId(), "FAILED");
        }
    }

    @Override
    public boolean renew(Job job) {
        return mapper.renew(job.eventId(), job.fence()) == 1;
    }

    @Override
    @Transactional
    public boolean beginExternalCall(Job job) {
        return mapper.beginExternalCall(job.eventId(), job.fence()) == 1;
    }

    @Override
    @Transactional
    public boolean confirmExternalCall(Job job) {
        return mapper.confirmExternalCall(job.eventId(), job.fence()) == 1;
    }

    @Override
    @Transactional
    public void recordUsage(Job job, int ocrCalls, int embeddingRequests, Long tokens, boolean uncertain) {
        Map<String, Object> usage = new HashMap<>();
        usage.put("eventId", job.eventId());
        usage.put("fence", job.fence());
        usage.put("ocrCalls", ocrCalls);
        usage.put("embeddingRequests", embeddingRequests);
        usage.put("tokens", tokens);
        usage.put("uncertain", uncertain);
        mapper.usage(usage);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> indexProtocol(Job job) {
        return Optional.ofNullable(mapper.indexProtocol(job.eventId(), job.fence()));
    }

    @Override
    @Transactional
    public boolean beginIndexProtocol(Job job, String protocol) {
        if (!STRUCTURED_PROTOCOL.equals(protocol) || !currentIndexSource(job, null)) {
            return false;
        }
        return mapper.beginIndexProtocol(job.eventId(), job.fence(), protocol) == 1;
    }

    @Override
    @Transactional
    public StageDecision beginIndexStage(Job job, StageKey key) {
        validateStageKey(key);
        if (!STRUCTURED_PROTOCOL.equals(mapper.indexProtocol(job.eventId(), job.fence()))
                || !currentIndexSource(job, key)) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
        Map<String, Object> parameters = stageParameters(job, key);
        Map<String, Object> existing = mapper.indexStage(job.eventId(), key.phase(), key.ordinal());
        if (existing == null && mapper.insertIndexStage(parameters) == 1) {
            return new StageDecision(true, null);
        }
        if (existing == null) {
            existing = mapper.indexStage(job.eventId(), key.phase(), key.ordinal());
        }
        if (!matchesStage(existing, job, key) || !"CONFIRMED".equals(text(existing, "call_state"))) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
        StageResult result = new StageResult(text(existing, "output_json"),
                text(existing, "output_sha256"), (int) number(existing, "request_count"),
                nullableNumber(existing, "total_tokens"));
        validateStageResult(result);
        validateResultForKey(key, result);
        return new StageDecision(false, result);
    }

    @Override
    @Transactional
    public boolean confirmIndexStage(Job job, StageKey key, StageResult result) {
        validateStageKey(key);
        validateStageResult(result);
        validateResultForKey(key, result);
        if (!STRUCTURED_PROTOCOL.equals(mapper.indexProtocol(job.eventId(), job.fence()))
                || !currentIndexSource(job, key)) {
            return false;
        }
        Map<String, Object> row = stageParameters(job, key);
        row.put("outputJson", result.outputJson());
        row.put("outputSha256", result.outputSha256());
        row.put("requestCount", result.requestCount());
        row.put("totalTokens", result.totalTokens());
        if (mapper.confirmIndexStage(row) != 1) {
            return false;
        }
        Map<String, Object> usage = new HashMap<>();
        usage.put("eventId", job.eventId());
        usage.put("fence", job.fence());
        usage.put("ocrCalls", "OCR_STEP".equals(key.phase()) ? 1 : 0);
        usage.put("embeddingRequests", "EMBED_BATCH".equals(key.phase()) ? 1 : 0);
        usage.put("tokens", "EMBED_BATCH".equals(key.phase()) ? result.totalTokens() : null);
        usage.put("uncertain", false);
        if (mapper.usage(usage) != 1) {
            throw new IllegalStateException("Index usage fence lost");
        }
        return true;
    }

    @Override
    @Transactional
    public void unknownIndexStage(Job job, StageKey key) {
        validateStageKey(key);
        // The paid call may have returned after withdrawal or lease expiry. Its original
        // intent fence and full input binding still identify the uncertain cost.
        if (mapper.lockAccount(job.accountId()) != null
                && job.eventId().equals(mapper.lockJobForUnknown(job.eventId(), job.accountId()))) {
            mapper.unknownIndexStage(stageParameters(job, key));
        }
    }

    @Override
    @Transactional
    public QueryEmbeddingDecision beginQueryEmbedding(QueryEmbeddingKey key) {
        validateQueryKey(key);
        int inserted = mapper.insertQueryIntent(queryParameters(key));
        if (inserted == 1) {
            return new QueryEmbeddingDecision(true, null);
        }
        QueryEmbeddingResult confirmed = readQueryEmbedding(key)
                .orElseThrow(() -> new StepExecutionException("OUTCOME_UNKNOWN"));
        return new QueryEmbeddingDecision(false, confirmed);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QueryEmbeddingResult> readQueryEmbedding(QueryEmbeddingKey key) {
        validateQueryKey(key);
        Map<String, Object> row = mapper.queryEmbedding(key.stepId());
        if (row == null) {
            return Optional.empty();
        }
        if (number(row, "account_id") != key.accountId()
                || !key.runId().equals(text(row, "run_id"))
                || !key.querySha256().equals(text(row, "query_sha256"))
                || !key.model().equals(text(row, "embedding_model"))
                || number(row, "dimensions") != key.dimensions()
                || !"CONFIRMED".equals(text(row, "call_state"))) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
        return Optional.of(new QueryEmbeddingResult(parseVector(text(row, "embedding"), key.dimensions()),
                (int) number(row, "embedding_requests"), nullableNumber(row, "embedding_tokens")));
    }

    @Override
    @Transactional
    public boolean confirmQueryEmbedding(QueryEmbeddingKey key, double[] vector,
            int requestCount, Long totalTokens) {
        validateQueryKey(key);
        if (requestCount < 1 || totalTokens != null && totalTokens < 0) {
            throw new IllegalArgumentException("Invalid embedding usage");
        }
        Map<String, Object> row = queryParameters(key);
        row.put("vector", vector(vector, key.dimensions()));
        row.put("requestCount", requestCount);
        row.put("totalTokens", totalTokens);
        return mapper.confirmQueryEmbedding(row) == 1;
    }

    @Override
    @Transactional
    public void obsolete(Job job) {
        if (mapper.lockAccount(job.accountId()) != null
                && job.eventId().equals(mapper.lockJob(job.eventId(), job.fence()))) {
            mapper.unknownUnconfirmedStages(job.eventId());
            if ("SOURCE_WITHDRAWN".equals(job.kind())) {
                mapper.deleteChunks(job.accountId(), job.sourceId(), null);
            }
            acknowledge(job);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Candidate> candidates(long accountId, Set<String> terms, Set<String> methodTags,
            double[] queryVector, String model, int dimensions) {
        Map<String, Set<String>> aliases = ChineseMathTerms.aliasesForMethods(methodTags);
        Set<String> inferredActual = methodTags.stream()
                .map(HandwritingMethodTaxonomy::canonicalMethod)
                .flatMap(Optional::stream).collect(Collectors.toUnmodifiableSet());
        Map<String, Object> query = new HashMap<>();
        query.put("accountId", accountId);
        query.put("terms", json.writeValueAsString(terms));
        query.put("methodAliases", json.writeValueAsString(aliases));
        query.put("literalPatterns",
                json.writeValueAsString(ChineseMathTerms.literalPatternsForMethods(aliases.keySet())));
        query.put("handwritingRecallEnabled", handwritingMethods.isRecallEnabled());
        query.put("inferredMethodTags", json.writeValueAsString(inferredActual));
        query.put("vector", vector(queryVector, dimensions));
        query.put("model", model);
        query.put("dimensions", dimensions);
        return mapper.candidates(query).stream().map(row -> new Candidate(text(row, "source_id"),
                number(row, "version"), text(row, "anchor_id"), SourceType.valueOf(text(row, "type")),
                text(row, "question_id"), text(row, "text"),
                Set.of(json.readValue(text(row, "method_tags"), String[].class)),
                ((Number) row.get("vector_score")).doubleValue(),
                ((Number) row.get("lexical_score")).doubleValue(),
                text(row, "answer_state"), inferredMethods(row))).toList();
    }

    private Set<String> inferredMethods(Map<String, Object> row) {
        if (!handwritingMethods.isRecallEnabled() || !"SAVED_ANSWER".equals(text(row, "type"))
                || text(row, "method_metadata") == null
                || !Boolean.TRUE.equals(row.get("method_projection_valid"))) {
            return Set.of();
        }
        try {
            HandwritingMethodMetadata metadata = HandwritingMethodMetadata.parse(text(row, "method_metadata"));
            metadata.validateBindings(number(row, "account_id"), text(row, "source_id"),
                    number(row, "version"), number(row, "index_revision"), text(row, "anchor_id"),
                    text(row, "anchor_sha256"), text(row, "text"));
            Set<String> projected = Set.of(json.readValue(text(row, "inferred_method_tags"), String[].class));
            Set<String> eligible = metadata.eligibleMethods();
            return projected.equals(eligible) ? eligible : Set.of();
        } catch (IllegalArgumentException exception) {
            return Set.of();
        }
    }

    @Override
    public String retrievalStepId(long accountId, String runId) {
        return mapper.retrievalStepId(accountId, runId);
    }

    @Override
    public boolean authorized(long accountId, String sourceId, long version, String anchorId) {
        return mapper.authorized(accountId, sourceId, version, anchorId) == 1;
    }

    private void acknowledge(Job job) {
        if (mapper.finish(job.eventId(), job.fence()) != 1) {
            throw new IllegalStateException("Index fence lost");
        }
        mapper.eventStatus(job.eventId(), job.accountId(), "DELIVERED");
    }

    private boolean currentIndexSource(Job job, StageKey key) {
        if (mapper.lockAccount(job.accountId()) == null
                || !job.eventId().equals(mapper.lockJob(job.eventId(), job.fence()))) {
            return false;
        }
        // Expected withdrawal/version loss must not cross SourceStore.get's transactional
        // exception boundary: catching NOT_FOUND afterwards leaves this transaction rollback-only.
        // Source changes share the account lock, so this visibility check and get are serialized.
        if (mapper.currentIndexEvent(job.eventId(), job.accountId(), job.sourceId(),
                job.version(), job.indexRevision()) != 1) {
            return false;
        }
        var content = sources.get(job.accountId(), job.sourceId(), job.version());
        return content.source().accountId() == job.accountId()
                && content.source().type()
                == com.betterlearning.server.multiagent.MultiAgentModels.SourceType.SAVED_ANSWER
                && job.sourceId().equals(content.source().sourceId())
                && "ACTIVE".equals(content.source().status())
                && content.source().currentVersion() == job.version()
                && "INDEX_PENDING".equals(content.version().status())
                && content.version().indexRevision() == job.indexRevision()
                && (key == null || key.sourceSha256().equals(content.version().contentSha256())
                && java.util.Objects.equals(key.fileRef(), content.version().fileRef()));
    }

    private void validateChunkMetadata(Job job,
            com.betterlearning.server.multiagent.MultiAgentModels.SourceContent content, Chunk chunk) {
        if (!job.sourceId().equals(chunk.anchor().sourceId())
                || job.version() != chunk.anchor().version()
                || !java.util.Objects.equals(chunk.text(), chunk.anchor().text())) {
            throw new IllegalArgumentException("Index method metadata anchor mismatch");
        }
        if (chunk.methodMetadataJson() == null) {
            return;
        }
        if (content.source().type() != com.betterlearning.server.multiagent.MultiAgentModels.SourceType.SAVED_ANSWER
                || !STRUCTURED_PROTOCOL.equals(mapper.indexProtocol(job.eventId(), job.fence()))) {
            throw new IllegalArgumentException("Invalid index method metadata source");
        }
        HandwritingMethodMetadata metadata = HandwritingMethodMetadata.parse(chunk.methodMetadataJson());
        if (!"HANDWRITING_STEP".equals(chunk.anchor().kind())
                || metadata.result().stepOrdinal() != chunk.anchor().ordinal()) {
            throw new IllegalArgumentException("Invalid index method metadata ordinal");
        }
        metadata.validateBindings(job.accountId(), job.sourceId(), job.version(), job.indexRevision(),
                chunk.anchor().anchorId(), chunk.anchor().contentSha256(), chunk.anchor().text());
        Map<String, Object> row = mapper.indexStage(job.eventId(), "OCR_STEP", metadata.result().stepOrdinal());
        StageKey key = new StageKey("OCR_STEP", metadata.result().stepOrdinal(),
                content.version().contentSha256(), content.version().fileRef(), metadata.imageSha256(),
                metadata.model(), 0, "handwriting-ocr-v1:math-methods-v1");
        if (!matchesStage(row, job, key) || !"CONFIRMED".equals(text(row, "call_state"))) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
        StageResult result = new StageResult(text(row, "output_json"), text(row, "output_sha256"),
                (int) number(row, "request_count"), nullableNumber(row, "total_tokens"));
        validateStageResult(result);
        HandwritingIndexOcr.Result confirmed = json.readValue(result.outputJson(), HandwritingIndexOcr.Result.class);
        if (!confirmed.equals(metadata.result())) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
    }

    private void verifyEmbeddingStages(Job job,
            com.betterlearning.server.multiagent.MultiAgentModels.SourceContent content,
            List<Chunk> chunks, String model, int dimensions) {
        List<Map<String, Object>> stages = mapper.confirmedEmbeddingStages(job.eventId());
        int cursor = 0;
        for (int ordinal = 0; ordinal < stages.size(); ordinal++) {
            Map<String, Object> row = stages.get(ordinal);
            if (number(row, "ordinal") != ordinal) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
            StageResult result = new StageResult(text(row, "output_json"), text(row, "output_sha256"),
                    (int) number(row, "request_count"), nullableNumber(row, "total_tokens"));
            validateStageResult(result);
            IndexStagePayloads.EmbeddingBatch decoded;
            try {
                decoded = IndexStagePayloads.decodeEmbedding(result.outputJson(), dimensions);
            } catch (IllegalArgumentException exception) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
            if (cursor + decoded.inputs().size() > chunks.size()) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
            List<Chunk> batch = chunks.subList(cursor, cursor + decoded.inputs().size());
            List<com.betterlearning.server.multiagent.MultiAgentModels.Anchor> anchors = batch.stream()
                    .map(Chunk::anchor).toList();
            StageKey key = new StageKey("EMBED_BATCH", ordinal, content.version().contentSha256(),
                    content.version().fileRef(), IndexStagePayloads.inputDigest(anchors),
                    model, dimensions, IndexStagePayloads.EMBEDDING_SCHEMA);
            if (!matchesStage(row, job, key)) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
            for (int position = 0; position < batch.size(); position++) {
                if (!IndexStagePayloads.input(batch.get(position).anchor())
                        .equals(decoded.inputs().get(position))
                        || !vector(batch.get(position).embedding(), dimensions)
                        .equals(vector(decoded.vectors().get(position), dimensions))) {
                    throw new StepExecutionException("OUTCOME_UNKNOWN");
                }
            }
            cursor += batch.size();
        }
        if (cursor != chunks.size()) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
    }

    private static Map<String, Object> stageParameters(Job job, StageKey key) {
        Map<String, Object> row = new HashMap<>();
        row.put("eventId", job.eventId());
        row.put("fence", job.fence());
        row.put("accountId", job.accountId());
        row.put("sourceId", job.sourceId());
        row.put("version", job.version());
        row.put("indexRevision", job.indexRevision());
        row.put("phase", key.phase());
        row.put("ordinal", key.ordinal());
        row.put("sourceSha256", key.sourceSha256());
        row.put("fileRef", key.fileRef());
        row.put("inputSha256", key.inputSha256());
        row.put("model", key.model());
        row.put("dimensions", key.dimensions());
        row.put("schemaVersion", key.schemaVersion());
        return row;
    }

    private static boolean matchesStage(Map<String, Object> row, Job job, StageKey key) {
        return row != null && row.get("purged_at") == null
                && job.eventId().equals(text(row, "event_id"))
                && key.phase().equals(text(row, "phase")) && key.ordinal() == number(row, "ordinal")
                && job.accountId() == number(row, "account_id")
                && job.sourceId().equals(text(row, "source_id"))
                && job.version() == number(row, "source_version")
                && job.indexRevision() == number(row, "index_revision")
                && key.sourceSha256().equals(text(row, "source_sha256"))
                && java.util.Objects.equals(key.fileRef(), text(row, "file_ref"))
                && key.inputSha256().equals(text(row, "input_sha256"))
                && key.model().equals(text(row, "model"))
                && key.dimensions() == number(row, "dimensions")
                && key.schemaVersion().equals(text(row, "schema_version"));
    }

    private static void validateStageKey(StageKey key) {
        if (key == null || key.ordinal() < 0 || !digest(key.sourceSha256())
                || !digest(key.inputSha256()) || key.model() == null || key.model().isBlank()
                || key.schemaVersion() == null || key.schemaVersion().isBlank()
                || key.schemaVersion().length() > 64 || !("OCR_STEP".equals(key.phase())
                && key.dimensions() == 0 && key.fileRef() != null
                && "handwriting-ocr-v1:math-methods-v1".equals(key.schemaVersion())
                || "EMBED_BATCH".equals(key.phase()) && key.dimensions() > 0
                && IndexStagePayloads.EMBEDDING_SCHEMA.equals(key.schemaVersion()))) {
            throw new IllegalArgumentException("Invalid index stage key");
        }
    }

    private void validateStageResult(StageResult result) {
        if (result == null || result.requestCount() != 1 || result.totalTokens() != null
                && result.totalTokens() < 0 || result.outputJson() == null
                || result.outputJson().length() > 8 * 1024 * 1024
                || result.outputJson().getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024
                || !digest(result.outputSha256())
                || !sha256(result.outputJson()).equals(result.outputSha256())) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
        try {
            var node = json.readTree(result.outputJson());
            if (node == null || !node.isObject()) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
        } catch (RuntimeException exception) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
    }

    private static void validateResultForKey(StageKey key, StageResult result) {
        if (!"EMBED_BATCH".equals(key.phase())) {
            return;
        }
        try {
            var batch = IndexStagePayloads.decodeEmbedding(result.outputJson(), key.dimensions());
            if (!IndexStagePayloads.inputDigestForInputs(batch.inputs()).equals(key.inputSha256())) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
        } catch (IllegalArgumentException exception) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Object> queryParameters(QueryEmbeddingKey key) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("accountId", key.accountId());
        parameters.put("runId", key.runId());
        parameters.put("stepId", key.stepId());
        parameters.put("querySha256", key.querySha256());
        parameters.put("model", key.model());
        parameters.put("dimensions", key.dimensions());
        return parameters;
    }

    private static void validateQueryKey(QueryEmbeddingKey key) {
        if (key == null || key.accountId() < 1 || key.runId() == null || key.runId().isBlank()
                || key.stepId() == null || key.stepId().isBlank() || key.querySha256() == null
                || !key.querySha256().matches("[0-9a-f]{64}") || key.model() == null
                || key.model().isBlank() || key.dimensions() < 1) {
            throw new IllegalArgumentException("Invalid query embedding key");
        }
    }

    private static double[] parseVector(String value, int dimensions) {
        if (value == null || !value.startsWith("[") || !value.endsWith("]")) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
        String[] components = value.substring(1, value.length() - 1).split(",", -1);
        if (components.length != dimensions) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
        double[] result = new double[dimensions];
        try {
            for (int index = 0; index < dimensions; index++) {
                result[index] = Double.parseDouble(components[index]);
            }
            vector(result, dimensions);
        } catch (IllegalArgumentException exception) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
        return result;
    }

    private static Long nullableNumber(Map<String, Object> row, String key) {
        Number value = (Number) row.get(key);
        return value == null ? null : value.longValue();
    }

    public static String vector(double[] values, int dimensions) {
        if (values == null || values.length != dimensions || dimensions < 1) {
            throw new IllegalArgumentException("Vector dimension mismatch");
        }
        StringBuilder result = new StringBuilder("[");
        double norm = 0;
        for (int index = 0; index < values.length; index++) {
            float value = (float) values[index];
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Invalid vector element");
            }
            norm += (double) value * value;
            if (index > 0) {
                result.append(',');
            }
            result.append(Float.toString(value));
        }
        if (norm == 0) {
            throw new IllegalArgumentException("Zero vector is not searchable");
        }
        return result.append(']').toString();
    }

    private static String text(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
