package com.betterlearning.server.agent.retrieval;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.betterlearning.server.agent.embedding.EmbeddingException;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingKey;
import com.betterlearning.server.agent.retrieval.ranking.ChineseMathTerms;
import com.betterlearning.server.agent.retrieval.ranking.HybridRanker;
import com.betterlearning.server.multiagent.EvidenceRetriever;
import com.betterlearning.server.multiagent.MultiAgentModels.Evidence;
import com.betterlearning.server.multiagent.MultiAgentModels.RetrievalQuery;
import com.betterlearning.server.multiagent.StepExecutionException;

/** 一次有界查询；证据 claim 保持不可变锚点原文，来源语义只作候选元数据。 */
public final class PersonalEvidenceRetriever implements EvidenceRetriever {
    private static final int MAX_CLAIM_CHARS = 4000;
    private final RetrievalIndexStore store;
    private final HttpEmbeddingClient embedding;
    private final EmbeddingProperties properties;
    private final HybridRanker ranker = new HybridRanker();

    public PersonalEvidenceRetriever(RetrievalIndexStore store, HttpEmbeddingClient embedding,
            EmbeddingProperties properties) {
        this.store = store;
        this.embedding = embedding;
        this.properties = properties;
    }

    @Override
    public List<Evidence> retrieve(RetrievalQuery query) {
        if (query == null || query.accountId() < 1 || query.runId() == null || query.text() == null
                || query.text().isBlank() || query.text().length() > 8000 || query.limit() < 1) {
            throw new IllegalArgumentException("Invalid retrieval query");
        }
        List<String> requestedLabels = query.methodTags() == null ? List.of() : query.methodTags();
        var methodAliases = ChineseMathTerms.queryAliases(requestedLabels);
        Set<String> labels = Set.copyOf(requestedLabels);
        Set<String> methods = labels.isEmpty() ? ChineseMathTerms.methods(query.text(), labels)
                : methodAliases.keySet();
        if (!labels.isEmpty() && methods.isEmpty()) {
            return List.of();
        }
        if (!properties.isEnabled()) {
            throw new EmbeddingException(EmbeddingException.Reason.DISABLED);
        }
        String stepId = store.retrievalStepId(query.accountId(), query.runId());
        if (stepId == null) {
            throw new IllegalStateException("No active retrieval step");
        }
        Set<String> terms = ChineseMathTerms.terms(query.text());
        QueryEmbeddingKey key = new QueryEmbeddingKey(query.accountId(), query.runId(), stepId,
                sha256(query.text()), properties.getModel(), properties.getDimensions());
        var decision = store.beginQueryEmbedding(key);
        double[] vector;
        if (decision.shouldCall()) {
            try {
                var result = embedding.embedWithStats(List.of(query.text()));
                vector = result.vectors().get(0);
                if (!store.confirmQueryEmbedding(key, vector, result.requestCount(), result.totalTokens())) {
                    throw new StepExecutionException("OUTCOME_UNKNOWN");
                }
                vector = store.readQueryEmbedding(key)
                        .orElseThrow(() -> new StepExecutionException("OUTCOME_UNKNOWN")).vector();
            } catch (RuntimeException exception) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
        } else {
            if (decision.confirmed() == null) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
            vector = decision.confirmed().vector();
        }
        var candidates = store.candidates(query.accountId(), terms, methods, vector,
                properties.getModel(), properties.getDimensions());
        var fittingCandidates = candidates.stream()
                .filter(candidate -> !candidate.text().isEmpty()
                && candidate.text().length() <= MAX_CLAIM_CHARS).toList();
        // 排序器的 excerpt 不得截断证据原文；总预算在下方按完整 claim 扣减。
        var ranked = ranker.rank(new HybridRanker.Query(query.text(), labels), fittingCandidates,
                new HybridRanker.Limits(Math.min(5, query.limit()), 3, Integer.MAX_VALUE), null);
        List<Evidence> evidence = new ArrayList<>();
        int remainingClaimChars = MAX_CLAIM_CHARS;
        for (var hit : ranked.evidence()) {
            var source = hit.candidate();
            if (!store.authorized(query.accountId(), source.sourceId(), source.version(), source.anchorId())) {
                continue;
            }
            String claim = source.text();
            if (claim.length() > remainingClaimChars) {
                continue;
            }
            evidence.add(new Evidence(UUID.randomUUID().toString(), query.runId(), stepId, "HISTORY",
                    source.sourceId(), source.version(), source.anchorId(), claim, null));
            remainingClaimChars -= claim.length();
        }
        return List.copyOf(evidence);
    }

    private static String sha256(String value) {
        try {
            var utf8 = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(utf8);
            return HexFormat.of().formatHex(digest.digest());
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid retrieval query text");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
