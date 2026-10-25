package com.betterlearning.server.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.config.HandwritingMethodsProperties;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Chunk;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Job;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageKey;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageResult;
import com.betterlearning.server.agent.retrieval.handwriting.HandwritingMethodMetadata;
import com.betterlearning.server.agent.retrieval.handwriting.HandwritingOcrValidator;
import com.betterlearning.server.agent.retrieval.ranking.ChineseMathTerms;
import com.betterlearning.server.agent.retrieval.ranking.HandwritingMethodTaxonomy;
import com.betterlearning.server.agent.service.HandwritingIndexOcr;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.retrieval.HandwritingSourceReader.PreparedStep;

import tools.jackson.databind.json.JsonMapper;

/** 分步骤持久调用；确认结果只在同一不可变输入与协议内复用。 */
public final class StructuredHandwritingIndexer {
    public static final String PROTOCOL = "HANDWRITING_METHODS_V1";
    public static final String OCR_SCHEMA = HandwritingIndexOcr.SCHEMA_VERSION
            + ":" + HandwritingMethodTaxonomy.VERSION;
    private final RetrievalIndexStore indexes;
    private final HttpEmbeddingClient embedding;
    private final EmbeddingProperties embeddingProperties;
    private final HandwritingMethodsProperties methodsProperties;
    private final HandwritingSourceReader handwriting;
    private final HandwritingIndexOcr ocr;

    public StructuredHandwritingIndexer(RetrievalIndexStore indexes, HttpEmbeddingClient embedding,
            EmbeddingProperties embeddingProperties, HandwritingMethodsProperties methodsProperties,
            HandwritingSourceReader handwriting, HandwritingIndexOcr ocr, JsonMapper json) {
        this.indexes = indexes;
        this.embedding = embedding;
        this.embeddingProperties = embeddingProperties;
        this.methodsProperties = methodsProperties;
        this.handwriting = handwriting;
        this.ocr = ocr;
    }

    public boolean shouldHandle(Job job, SourceContent content) {
        if (content.source().type() != SourceType.SAVED_ANSWER) {
            return false;
        }
        var pinned = indexes.indexProtocol(job);
        if (pinned.isPresent()) {
            if (!PROTOCOL.equals(pinned.get())) {
                throw new IllegalStateException("Unsupported index protocol");
            }
            return true;
        }
        // Old anchors are immutable; ordinary reindex must not silently add a new image call.
        return methodsProperties.isEnabled() && "SOURCE_VERSION_CREATED".equals(job.kind())
                && content.anchors().isEmpty();
    }

    public void process(Job job, SourceContent content) {
        if (content.source().type() != SourceType.SAVED_ANSWER || !content.anchors().isEmpty()) {
            throw new IllegalArgumentException("Structured OCR requires a new saved-answer index");
        }
        if (!indexes.beginIndexProtocol(job, PROTOCOL)) {
            throw unknown();
        }
        List<PreparedStep> prepared = handwriting.prepare(content.version().fileRef());
        if (prepared.isEmpty()) {
            if (!indexes.complete(job, List.of(), embeddingProperties.getModel(), embeddingProperties.getDimensions())) {
                throw new IllegalStateException("Empty index publication was fenced out");
            }
            return;
        }
        List<Anchor> anchors = new ArrayList<>();
        List<String> metadata = new ArrayList<>();
        String ocrModel = ocr.model();
        if (ocrModel == null || ocrModel.isBlank()) {
            throw new IllegalStateException("Handwriting OCR model is not configured");
        }
        for (PreparedStep step : prepared) {
            StageKey key = key(content, "OCR_STEP", step.ordinal(), step.imageSha256(),
                    ocrModel, 0, OCR_SCHEMA);
            var decision = indexes.beginIndexStage(job, key);
            HandwritingIndexOcr.Result result;
            if (decision.shouldCall()) {
                try {
                    result = HandwritingOcrValidator.validate(ocr.recognize(step.image(), step.ordinal(),
                            step.pixelWidth(), step.pixelHeight()), step.ordinal(), step.pixelWidth(), step.pixelHeight());
                    String output = HandwritingOcrValidator.encode(result);
                    if (!indexes.confirmIndexStage(job, key,
                            new StageResult(output, IndexStagePayloads.sha256(output), 1, null))) {
                        throw unknown();
                    }
                } catch (RuntimeException exception) {
                    markUnknown(job, key);
                    throw unknown();
                }
            } else {
                StageResult confirmed = requireConfirmed(decision.confirmed());
                result = HandwritingOcrValidator.parse(confirmed.outputJson(), step.ordinal(),
                        step.pixelWidth(), step.pixelHeight());
            }
            var draft = handwriting.anchorDraft(step, result.transcript());
            String identity = content.source().sourceId() + ":" + content.version().version()
                    + ":" + draft.kind() + ":" + draft.ordinal();
            Anchor anchor = new Anchor(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(),
                    content.source().sourceId(), content.version().version(), draft.kind(), draft.ordinal(),
                    draft.contentSha256(), draft.locatorJson(), draft.text());
            anchors.add(anchor);
            metadata.add(new HandwritingMethodMetadata(1, job.accountId(), job.sourceId(), job.version(),
                    job.indexRevision(), anchor.anchorId(), anchor.contentSha256(), step.imageSha256(),
                    step.pixelWidth(), step.pixelHeight(), ocrModel, result).encode());
        }
        List<double[]> vectors = embeddings(job, content, anchors);
        List<Chunk> chunks = new ArrayList<>();
        for (int index = 0; index < anchors.size(); index++) {
            Anchor anchor = anchors.get(index);
            chunks.add(new Chunk(anchor, content.source().originId(), anchor.text(),
                    ChineseMathTerms.methods(anchor.text(), Set.of()), ChineseMathTerms.terms(anchor.text()),
                    vectors.get(index), metadata.get(index)));
        }
        if (!indexes.complete(job, chunks, embeddingProperties.getModel(), embeddingProperties.getDimensions())) {
            throw new IllegalStateException("Index publication was fenced out");
        }
    }

    private List<double[]> embeddings(Job job, SourceContent content, List<Anchor> anchors) {
        List<double[]> vectors = new ArrayList<>();
        int batchSize = embeddingProperties.getBatchSize();
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Invalid embedding batch size");
        }
        for (int start = 0, ordinal = 0; start < anchors.size(); start += batchSize, ordinal++) {
            List<Anchor> batch = anchors.subList(start, Math.min(anchors.size(), start + batchSize));
            StageKey key = key(content, "EMBED_BATCH", ordinal, IndexStagePayloads.inputDigest(batch),
                    embeddingProperties.getModel(), embeddingProperties.getDimensions(), IndexStagePayloads.EMBEDDING_SCHEMA);
            var decision = indexes.beginIndexStage(job, key);
            StageResult confirmed;
            if (decision.shouldCall()) {
                try {
                    var result = embedding.embedWithStats(batch.stream().map(Anchor::text).toList());
                    if (result.requestCount() != 1 || !embeddingProperties.getModel().equals(result.responseModel())) {
                        throw new IllegalStateException("Embedding must confirm exactly one configured batch");
                    }
                    String output = IndexStagePayloads.encodeEmbedding(batch, result.vectors(),
                            embeddingProperties.getDimensions());
                    confirmed = new StageResult(output, IndexStagePayloads.sha256(output), 1, result.totalTokens());
                    if (!indexes.confirmIndexStage(job, key, confirmed)) {
                        throw unknown();
                    }
                } catch (RuntimeException exception) {
                    markUnknown(job, key);
                    throw unknown();
                }
            } else {
                confirmed = requireConfirmed(decision.confirmed());
            }
            var result = IndexStagePayloads.decodeEmbedding(confirmed.outputJson(), embeddingProperties.getDimensions());
            if (!result.inputs().equals(batch.stream().map(IndexStagePayloads::input).toList())) {
                throw unknown();
            }
            vectors.addAll(result.vectors());
        }
        return List.copyOf(vectors);
    }

    private void markUnknown(Job job, StageKey key) {
        try {
            indexes.unknownIndexStage(job, key);
        } catch (RuntimeException ignored) {
            // The durable CALL_INTENT remains the recovery authority. Preserve the
            // unknown outcome so a later job failure cannot report this as unpaid.
        }
    }

    private static StageKey key(SourceContent content, String phase, int ordinal, String input,
            String model, int dimensions, String schema) {
        return new StageKey(phase, ordinal, content.version().contentSha256(), content.version().fileRef(),
                input, model, dimensions, schema);
    }

    private static StageResult requireConfirmed(StageResult result) {
        if (result == null || result.outputJson() == null
                || !IndexStagePayloads.sha256(result.outputJson()).equals(result.outputSha256())) {
            throw unknown();
        }
        return result;
    }

    private static StepExecutionException unknown() {
        return new StepExecutionException("OUTCOME_UNKNOWN");
    }
}
