package com.betterlearning.server.retrieval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingDecision;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingKey;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingResult;
import com.betterlearning.server.agent.retrieval.ranking.ChineseMathTerms;
import com.betterlearning.server.agent.retrieval.ranking.HybridRanker.Candidate;
import com.betterlearning.server.agent.retrieval.ranking.HybridRanker.SourceType;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.retrieval.mapper.RetrievalMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/** 只持久化可重建索引；来源和锚点由 P1 SourceStore 保持为事实源。 */
@Repository
public class MybatisRetrievalIndexStore implements RetrievalIndexStore {
    private final RetrievalMapper mapper;
    private final SourceStore sources;
    private final JsonMapper json;

    public MybatisRetrievalIndexStore(RetrievalMapper mapper, SourceStore sources, JsonMapper json) {
        this.mapper = mapper;
        this.sources = sources;
        this.json = json;
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
        if (!"NOT_SENT".equals(text(row, "call_state"))) {
            // 租约超时只证明 worker 失联，无法证明外部供应商没有计费或返回结果。
            fail(job, "OUTCOME_UNKNOWN");
            return Optional.empty();
        }
        return Optional.of(job);
    }

    @Override
    @Transactional
    public boolean complete(Job job, List<Chunk> chunks, String model, int dimensions) {
        if (!job.eventId().equals(mapper.lockJob(job.eventId(), job.fence()))) {
            return false;
        }
        mapper.lockAccount(job.accountId());
        var content = sources.get(job.accountId(), job.sourceId(), job.version());
        if (content.source().currentVersion() != job.version()
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
        // 显式 reindex 可复用已提交的稳定锚点，避免重新 OCR。
        var existing = content.anchors();
        if (existing.isEmpty()) {
            sources.addAnchors(job.accountId(), job.sourceId(), job.version(), job.indexRevision(),
                    chunks.stream().map(Chunk::anchor).toList());
        } else if (existing.size() != chunks.size() || chunks.stream().anyMatch(chunk -> existing.stream()
                .noneMatch(anchor -> anchor.anchorId().equals(chunk.anchor().anchorId())
                && anchor.contentSha256().equals(chunk.anchor().contentSha256())
                && anchor.locatorJson().equals(chunk.anchor().locatorJson())))) {
            throw new IllegalStateException("Immutable anchor mismatch");
        }
        mapper.deleteChunks(job.accountId(), job.sourceId(), job.version());
        for (Chunk chunk : chunks) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", UUID.randomUUID().toString());
            row.put("accountId", job.accountId());
            row.put("sourceId", job.sourceId());
            row.put("version", job.version());
            row.put("indexRevision", job.indexRevision());
            row.put("anchorId", chunk.anchor().anchorId());
            row.put("questionId", chunk.questionId());
            row.put("text", chunk.text());
            row.put("methodTags", json.writeValueAsString(chunk.methodTags()));
            row.put("terms", json.writeValueAsString(chunk.terms()));
            row.put("vector", vector(chunk.embedding(), dimensions));
            row.put("model", model);
            row.put("dimensions", dimensions);
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
        if (job.eventId().equals(mapper.lockJob(job.eventId(), job.fence()))) {
            mapper.lockAccount(job.accountId());
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
        if (job.eventId().equals(mapper.lockJob(job.eventId(), job.fence()))) {
            mapper.lockAccount(job.accountId());
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
        Map<String, Object> query = Map.of("accountId", accountId, "terms", json.writeValueAsString(terms),
                "methodAliases", json.writeValueAsString(aliases),
                "literalPatterns", json.writeValueAsString(ChineseMathTerms.literalPatternsForMethods(aliases.keySet())),
                "vector", vector(queryVector, dimensions),
                "model", model, "dimensions", dimensions);
        return mapper.candidates(query).stream().map(row -> new Candidate(text(row, "source_id"),
                number(row, "version"), text(row, "anchor_id"), SourceType.valueOf(text(row, "type")),
                text(row, "question_id"), text(row, "text"),
                Set.of(json.readValue(text(row, "method_tags"), String[].class)),
                ((Number) row.get("vector_score")).doubleValue(),
                ((Number) row.get("lexical_score")).doubleValue(),
                text(row, "answer_state"))).toList();
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
