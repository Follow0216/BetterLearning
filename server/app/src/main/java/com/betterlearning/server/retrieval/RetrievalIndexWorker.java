package com.betterlearning.server.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.embedding.EmbeddingException;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Chunk;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Job;
import com.betterlearning.server.agent.retrieval.anchor.MarkdownAnchorParser;
import com.betterlearning.server.agent.retrieval.ranking.ChineseMathTerms;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.AnchorLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.MarkdownLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.StepExecutionException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** 未外呼任务可重投；模型/OCR 前提交意图，发布时再检查源版本及租约 fence。 */
@Component
public class RetrievalIndexWorker {
    private final SourceStore sources;
    private final RetrievalIndexStore indexes;
    private final HttpEmbeddingClient embedding;
    private final EmbeddingProperties properties;
    private final JsonMapper json;
    private final HandwritingSourceReader handwriting;
    private final StructuredHandwritingIndexer structured;

    public RetrievalIndexWorker(SourceStore sources, RetrievalIndexStore indexes,
            HttpEmbeddingClient embedding, EmbeddingProperties properties, JsonMapper json,
            HandwritingSourceReader handwriting) {
        this(sources, indexes, embedding, properties, json, handwriting, null);
    }

    @Autowired
    public RetrievalIndexWorker(SourceStore sources, RetrievalIndexStore indexes,
            HttpEmbeddingClient embedding, EmbeddingProperties properties, JsonMapper json,
            HandwritingSourceReader handwriting, StructuredHandwritingIndexer structured) {
        this.sources = sources;
        this.indexes = indexes;
        this.embedding = embedding;
        this.properties = properties;
        this.json = json;
        this.handwriting = handwriting;
        this.structured = structured;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void tick() {
        indexes.claim().ifPresent(this::process);
    }

    public void process(Job job) {
        var heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "retrieval-index-lease");
            thread.setDaemon(true);
            return thread;
        });
        var renewal = heartbeat.scheduleWithFixedDelay(() -> {
            try {
                indexes.renew(job);
            } catch (RuntimeException ignored) {
                // 发布仍由数据库 lease/fence 拒绝；不在日志记录上下文或源正文。
            }
        }, 30, 30, TimeUnit.SECONDS);
        try {
            if (job.attempts() > 3) {
                indexes.fail(job, "INDEX_RETRY_EXHAUSTED");
                return;
            }
            if ("SOURCE_WITHDRAWN".equals(job.kind())) {
                indexes.obsolete(job);
                return;
            }
            SourceContent content = sources.get(job.accountId(), job.sourceId(), job.version());
            if (content.source().currentVersion() != job.version()
                    || content.version().indexRevision() != job.indexRevision()
                    || !"ACTIVE".equals(content.source().status())
                    || !"INDEX_PENDING".equals(content.version().status())
                    || content.source().type() == SourceType.TUTOR_SNAPSHOT) {
                indexes.obsolete(job);
                return;
            }
            if (!properties.isEnabled()) {
                throw new EmbeddingException(EmbeddingException.Reason.DISABLED);
            }
            if (structured != null && structured.shouldHandle(job, content)) {
                structured.process(job, content);
                return;
            }
            List<Anchor> anchors = parse(job, content);
            if (anchors.size() > 100) {
                throw new IllegalArgumentException("Index document exceeds bounded chunk count");
            }
            if (anchors.isEmpty()) {
                indexes.complete(job, List.of(), properties.getModel(), properties.getDimensions());
                return;
            }
            Set<String> titleMethods = content.source().type() == SourceType.NOTE
                    ? ChineseMathTerms.methodsInText(content.source().title()) : Set.of();
            requireExternalCallFence(indexes.beginExternalCall(job));
            var embedded = embedding.embedWithStats(anchors.stream().map(Anchor::text).toList());
            indexes.recordUsage(job, 0, embedded.requestCount(), embedded.totalTokens(), false);
            requireExternalCallFence(indexes.confirmExternalCall(job));
            List<double[]> vectors = embedded.vectors();
            List<Chunk> chunks = new ArrayList<>();
            String questionId = content.source().type() == SourceType.QUESTION
                    || content.source().type() == SourceType.SAVED_ANSWER ? content.source().originId() : null;
            for (int index = 0; index < anchors.size(); index++) {
                Anchor anchor = anchors.get(index);
                Set<String> methods = new HashSet<>(ChineseMathTerms.methods(anchor.text(), Set.of()));
                Set<String> additional = new HashSet<>(titleMethods);
                additional.removeAll(ChineseMathTerms.negatedMethods(anchor.text()));
                methods.addAll(additional);
                chunks.add(new Chunk(anchor, questionId, anchor.text(),
                        Set.copyOf(methods), ChineseMathTerms.terms(anchor.text()),
                        vectors.get(index)));
            }
            indexes.complete(job, chunks, properties.getModel(), properties.getDimensions());
        } catch (EmbeddingException exception) {
            indexes.recordUsage(job, 0, 0, null, exception.reason() == EmbeddingException.Reason.UNAVAILABLE
                    || exception.reason() == EmbeddingException.Reason.INVALID_RESPONSE);
            indexes.fail(job, "EMBEDDING_" + exception.reason().name());
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.NOT_FOUND) {
                indexes.obsolete(job);
            } else {
                indexes.fail(job, "SOURCE_UNAVAILABLE");
            }
        } catch (HandwritingSourceReader.HandwritingReadException exception) {
            indexes.recordUsage(job, exception.ocrCalls(), 0, null, exception.ocrCalls() > 0);
            indexes.fail(job, "HANDWRITING_" + exception.reason());
        } catch (StepExecutionException exception) {
            indexes.fail(job, exception.reason());
        } catch (RuntimeException exception) {
            // 原文、供应商响应和文件路径不进入 outbox 诊断。
            indexes.fail(job, "INDEX_PROCESSING_FAILED");
        } finally {
            renewal.cancel(false);
            heartbeat.shutdownNow();
        }
    }

    private List<Anchor> parse(Job job, SourceContent content) {
        if (!content.anchors().isEmpty()) {
            if (content.source().type() == SourceType.SAVED_ANSWER) {
                handwriting.validateReuse(content);
            }
            return content.anchors();
        }
        List<Anchor> anchors = new ArrayList<>();
        if (content.source().type() == SourceType.SAVED_ANSWER) {
            requireExternalCallFence(indexes.beginExternalCall(job));
            var parsed = handwriting.read(content.version().fileRef());
            indexes.recordUsage(job, parsed.ocrCalls(), 0, null, false);
            requireExternalCallFence(indexes.confirmExternalCall(job));
            for (var draft : parsed.anchors()) {
                anchors.add(anchor(content, draft.kind(), draft.ordinal(), draft.contentSha256(),
                        draft.locatorJson(), draft.text()));
            }
            return anchors;
        }
        String kind = content.source().type() == SourceType.QUESTION ? "QUESTION_BLOCK" : "MARKDOWN_BLOCK";
        for (var block : MarkdownAnchorParser.parse(content.version().bodyMarkdown())) {
            String locator = json.writeValueAsString(new AnchorLocator(1,
                    new MarkdownLocator(block.ordinal(), block.startOffset(), block.endOffset()), null));
            anchors.add(anchor(content, kind, block.ordinal(), block.contentSha256(), locator, block.text()));
        }
        return anchors;
    }

    private static void requireExternalCallFence(boolean current) {
        if (!current) {
            throw new IllegalStateException("Index external-call fence lost");
        }
    }

    private static Anchor anchor(SourceContent content, String kind, int ordinal, String digest,
            String locator, String text) {
        String identity = content.source().sourceId() + ":" + content.version().version() + ":" + kind + ":" + ordinal;
        return new Anchor(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(),
                content.source().sourceId(), content.version().version(), kind, ordinal, digest, locator, text);
    }
}
