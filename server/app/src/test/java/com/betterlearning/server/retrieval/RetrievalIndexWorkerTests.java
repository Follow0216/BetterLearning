package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.embedding.EmbeddingException;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Chunk;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Job;
import com.betterlearning.server.agent.retrieval.anchor.MarkdownAnchorParser;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.Source;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceVersion;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.retrieval.HandwritingSourceReader.HandwritingReadException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import tools.jackson.databind.json.JsonMapper;

class RetrievalIndexWorkerTests {
    private static final long ACCOUNT = 7;
    private static final String SOURCE = "source-1";
    private final SourceStore sources = mock(SourceStore.class);
    private final RetrievalIndexStore indexes = mock(RetrievalIndexStore.class);
    private final HttpEmbeddingClient embedding = mock(HttpEmbeddingClient.class);
    private final HandwritingSourceReader handwriting = mock(HandwritingSourceReader.class);
    private final EmbeddingProperties properties = new EmbeddingProperties();
    private final Job job = new Job("event-1", ACCOUNT, SOURCE, 1,
            "SOURCE_VERSION_CREATED", 1, 1);
    private RetrievalIndexWorker worker;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setModel("test-embedding");
        properties.setDimensions(2);
        worker = new RetrievalIndexWorker(sources, indexes, embedding, properties,
                JsonMapper.builder().build(), handwriting);
        when(indexes.beginExternalCall(any())).thenReturn(true);
        when(indexes.confirmExternalCall(any())).thenReturn(true);
    }

    @Test
    void snapshotOldVersionAndOldIndexRevisionAreObsoleteBeforeAnyModelCall() {
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.TUTOR_SNAPSHOT, 1, 1, "", List.of()),
                content(SourceType.NOTE, 2, 1, "配方法", List.of()),
                content(SourceType.NOTE, 1, 2, "配方法", List.of()));

        worker.process(job);
        worker.process(job);
        worker.process(job);

        verify(indexes, times(3)).obsolete(job);
        verifyNoInteractions(embedding, handwriting);
    }

    @Test
    void disabledEmbeddingDoesNotCallOcrAndRecordsVisibleFailure() {
        properties.setEnabled(false);
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.SAVED_ANSWER, 1, 1, null, List.of()));

        worker.process(job);

        verify(indexes).recordUsage(job, 0, 0, null, false);
        verify(indexes).fail(job, "EMBEDDING_DISABLED");
        verifyNoInteractions(embedding, handwriting);
    }

    @Test
    void providerFailureMarksUsageUncertainWithoutPublishingChunks() {
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.NOTE, 1, 1, "配方法求解", List.of()));
        when(embedding.embedWithStats(any())).thenThrow(
                new EmbeddingException(EmbeddingException.Reason.UNAVAILABLE));

        worker.process(job);

        verify(indexes).recordUsage(job, 0, 0, null, true);
        verify(indexes).fail(job, "EMBEDDING_UNAVAILABLE");
        verify(indexes).beginExternalCall(job);
        verifyNoInteractions(handwriting);
    }

    @Test
    void controlledNoteTitleAddsMethodWithoutChangingRawAnchorOrEmbeddingInput() {
        String body = "$$x^2+2x=(x+1)^2-1$$";
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.NOTE, 1, 1, body, List.of(), "配方法"));
        embeddingForOneBlock();

        worker.process(job);

        Chunk chunk = singleCompletedChunk();
        assertThat(chunk.methodTags()).containsExactly("配方法");
        assertThat(chunk.text()).isEqualTo(body);
        assertThat(chunk.anchor().text()).isEqualTo(body);
        assertThat(chunk.anchor().contentSha256())
                .isEqualTo(MarkdownAnchorParser.parse(body).get(0).contentSha256());
        assertThat(chunk.anchor().locatorJson()).contains("markdownLocator");
        verify(embedding).embedWithStats(List.of(body));
        verifyNoInteractions(handwriting);
    }

    @Test
    void embeddedTitleMethodIsControlledButNoiseAndLongTailTitleAreNotAdded() {
        String body = "$$x^2+2x=(x+1)^2-1$$";
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.NOTE, 1, 1, body, List.of(), "我的配方法笔记"),
                content(SourceType.NOTE, 1, 1, body, List.of(), "普通公式"),
                content(SourceType.NOTE, 1, 1, body, List.of(), "概率积分"),
                content(SourceType.NOTE, 1, 1, body, List.of(), "柯西施瓦茨不等式"),
                content(SourceType.NOTE, 1, 1, body, List.of(), "不使用配方法"));
        embeddingForOneBlock();

        for (int attempt = 0; attempt < 5; attempt++) {
            worker.process(job);
        }

        ArgumentCaptor<List<Chunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(indexes, times(5)).complete(eq(job), chunks.capture(), eq("test-embedding"), eq(2));
        assertThat(chunks.getAllValues()).extracting(batch -> batch.get(0).methodTags())
                .containsExactly(Set.of("配方法"), Set.of(), Set.of(), Set.of(), Set.of());
        assertThat(chunks.getAllValues()).allSatisfy(batch -> {
            assertThat(batch.get(0).text()).isEqualTo(body);
            assertThat(batch.get(0).anchor().text()).isEqualTo(body);
        });
        verify(embedding, times(5)).embedWithStats(List.of(body));
    }

    @Test
    void rawNoteDenialOverridesAdditionalTitleMethod() {
        String body = "本题不使用换元法。x²−9=(x−3)(x+3)";
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.NOTE, 1, 1, body, List.of(), "换元法"));
        embeddingForOneBlock();

        worker.process(job);

        Chunk chunk = singleCompletedChunk();
        assertThat(chunk.methodTags()).isEmpty();
        assertThat(chunk.text()).isEqualTo(body);
        assertThat(chunk.anchor().contentSha256())
                .isEqualTo(MarkdownAnchorParser.parse(body).get(0).contentSha256());
        verify(embedding).embedWithStats(List.of(body));
    }

    @Test
    void savedAnswerTitleAndQuestionIdDoNotInferMethodWithoutQuestionMetadata() {
        String formula = "$$x^2+2x=(x+1)^2-1$$";
        Anchor anchor = new Anchor("anchor-1", SOURCE, 1, "HANDWRITING_STEP", 0,
                "digest", "{}", formula);
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.SAVED_ANSWER, 1, 1, null, List.of(anchor), "配方法"));
        embeddingForOneBlock();

        worker.process(job);

        Chunk chunk = singleCompletedChunk();
        assertThat(chunk.methodTags()).isEmpty();
        assertThat(chunk.questionId()).isEqualTo("question-1");
        assertThat(chunk.anchor()).isSameAs(anchor);
        assertThat(chunk.text()).isEqualTo(formula);
        verify(handwriting).validateReuse(any());
        verify(embedding).embedWithStats(List.of(formula));
        verify(sources).get(ACCOUNT, SOURCE, 1);
        verifyNoMoreInteractions(sources);
    }

    @Test
    void ocrProviderFailurePreservesAttemptedCallCountAndUncertainty() {
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.SAVED_ANSWER, 1, 1, null, List.of()));
        HandwritingReadException failure = mock(HandwritingReadException.class);
        when(failure.reason()).thenReturn("OCR_FAILED");
        when(failure.ocrCalls()).thenReturn(1);
        when(handwriting.read("file-ref")).thenThrow(failure);

        worker.process(job);

        verify(indexes).recordUsage(job, 1, 0, null, true);
        verify(indexes).fail(job, "HANDWRITING_OCR_FAILED");
        verify(indexes).beginExternalCall(job);
        verifyNoInteractions(embedding);
    }

    @Test
    void committedAnchorsAreReusedWithoutOcrAndEmbeddingUsageIsRecorded() {
        Anchor anchor = new Anchor("anchor-1", SOURCE, 1, "HANDWRITING_STEP", 0,
                "digest", "{}", "配方法步骤");
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.SAVED_ANSWER, 1, 1, null, List.of(anchor)));
        when(embedding.embedWithStats(List.of("配方法步骤"))).thenReturn(
                new HttpEmbeddingClient.EmbeddingResult(List.of(new double[]{1, 0}),
                1, 5L, 5L, "test-embedding"));

        worker.process(job);

        verify(handwriting).validateReuse(any());
        verifyNoMoreInteractions(handwriting);
        verify(indexes).recordUsage(job, 0, 1, 5L, false);
        InOrder externalCall = inOrder(indexes, embedding);
        externalCall.verify(indexes).beginExternalCall(job);
        externalCall.verify(embedding).embedWithStats(List.of("配方法步骤"));
        externalCall.verify(indexes).recordUsage(job, 0, 1, 5L, false);
        externalCall.verify(indexes).confirmExternalCall(job);
        ArgumentCaptor<List<Chunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(indexes).complete(eq(job), chunks.capture(), eq("test-embedding"), eq(2));
        assertThat(chunks.getValue()).hasSize(1);
        assertThat(chunks.getValue().get(0).anchor()).isEqualTo(anchor);
    }

    @Test
    void changedOriginalBlockStopsCommittedAnchorReplayBeforeEmbedding() {
        Anchor anchor = new Anchor("anchor-1", SOURCE, 1, "HANDWRITING_STEP", 0,
                "digest", "{}", "配方法步骤");
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.SAVED_ANSWER, 1, 1, null, List.of(anchor)));
        HandwritingReadException failure = mock(HandwritingReadException.class);
        when(failure.reason()).thenReturn("SOURCE_REUSE_INVALID");
        when(failure.ocrCalls()).thenReturn(0);
        doThrow(failure).when(handwriting).validateReuse(any());

        worker.process(job);

        verify(indexes).recordUsage(job, 0, 0, null, false);
        verify(indexes).fail(job, "HANDWRITING_SOURCE_REUSE_INVALID");
        verifyNoMoreInteractions(indexes);
        verifyNoInteractions(embedding);
        verify(handwriting).validateReuse(any());
        verifyNoMoreInteractions(handwriting);
    }

    @Test
    void blankMarkdownCompletesWithoutCallingEmbeddingOnEmptyInput() {
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.NOTE, 1, 1, " \n ", List.of()));

        worker.process(job);

        verify(indexes).complete(job, List.of(), "test-embedding", 2);
        verifyNoInteractions(embedding, handwriting);
    }

    @Test
    void unpaidJobCannotSendWhenIntentPersistenceFails() {
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.NOTE, 1, 1, "配方法求解", List.of()));
        when(indexes.beginExternalCall(job)).thenReturn(false);

        worker.process(job);

        verify(indexes).beginExternalCall(job);
        verify(indexes).fail(job, "INDEX_PROCESSING_FAILED");
        verifyNoInteractions(embedding, handwriting);
    }

    @Test
    void processCrashAfterIntentDoesNotConfirmOrCompleteExternalCall() {
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.NOTE, 1, 1, "配方法求解", List.of()));
        when(embedding.embedWithStats(any())).thenThrow(new AssertionError("simulated process death"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> worker.process(job))
                .isInstanceOf(AssertionError.class);

        verify(indexes).beginExternalCall(job);
        verify(embedding, times(1)).embedWithStats(any());
        verify(indexes, times(0)).confirmExternalCall(job);
        verify(indexes, times(0)).complete(eq(job), any(), any(), eq(2));
        verify(indexes, times(0)).fail(eq(job), any());
    }

    @Test
    void ocrAndEmbeddingEachPersistIntentBeforeCall() {
        when(sources.get(ACCOUNT, SOURCE, 1)).thenReturn(
                content(SourceType.SAVED_ANSWER, 1, 1, null, List.of()));
        when(handwriting.read("file-ref")).thenReturn(new HandwritingSourceReader.ParsedHandwriting(
                List.of(new HandwritingSourceReader.AnchorDraft("HANDWRITING_STEP", 0,
                "步骤", "digest", "{}")), 1));
        when(embedding.embedWithStats(List.of("步骤"))).thenReturn(
                new HttpEmbeddingClient.EmbeddingResult(List.of(new double[]{1, 0}),
                1, 5L, 5L, "test-embedding"));

        worker.process(job);

        InOrder calls = inOrder(indexes, handwriting, embedding);
        calls.verify(indexes).beginExternalCall(job);
        calls.verify(handwriting).read("file-ref");
        calls.verify(indexes).recordUsage(job, 1, 0, null, false);
        calls.verify(indexes).confirmExternalCall(job);
        calls.verify(indexes).beginExternalCall(job);
        calls.verify(embedding).embedWithStats(List.of("步骤"));
        calls.verify(indexes).recordUsage(job, 0, 1, 5L, false);
        calls.verify(indexes).confirmExternalCall(job);
        verify(embedding, times(1)).embedWithStats(any());
    }

    private SourceContent content(SourceType type, long currentVersion, long indexRevision,
            String body, List<Anchor> anchors) {
        return content(type, currentVersion, indexRevision, body, anchors, "title");
    }

    private SourceContent content(SourceType type, long currentVersion, long indexRevision,
            String body, List<Anchor> anchors, String title) {
        Instant created = Instant.parse("2026-10-25T00:00:00Z");
        Source source = new Source(SOURCE, ACCOUNT, type, "question-1", title, "ACTIVE",
                currentVersion, created);
        SourceVersion version = new SourceVersion(SOURCE, 1, "digest", "INDEX_PENDING",
                "DRAFT", "file-ref", body, created, indexRevision);
        return new SourceContent(source, version, anchors);
    }

    private void embeddingForOneBlock() {
        when(embedding.embedWithStats(any())).thenReturn(
                new HttpEmbeddingClient.EmbeddingResult(List.of(new double[] {1, 0}),
                1, 5L, 5L, "test-embedding"));
    }

    private Chunk singleCompletedChunk() {
        ArgumentCaptor<List<Chunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(indexes).complete(eq(job), chunks.capture(), eq("test-embedding"), eq(2));
        assertThat(chunks.getValue()).hasSize(1);
        return chunks.getValue().get(0);
    }
}
