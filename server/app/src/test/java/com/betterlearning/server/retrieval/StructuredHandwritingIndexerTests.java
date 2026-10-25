package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.config.HandwritingMethodsProperties;
import com.betterlearning.server.agent.embedding.EmbeddingException;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Chunk;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Job;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageDecision;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageKey;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageResult;
import com.betterlearning.server.agent.retrieval.handwriting.HandwritingMethodMetadata;
import com.betterlearning.server.agent.service.HandwritingIndexOcr;
import com.betterlearning.server.agent.service.HandwritingIndexOcr.MethodCandidate;
import com.betterlearning.server.agent.service.HandwritingIndexOcr.Region;
import com.betterlearning.server.agent.service.HandwritingIndexOcr.Result;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.Source;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceVersion;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.retrieval.HandwritingSourceReader.AnchorDraft;
import com.betterlearning.server.retrieval.HandwritingSourceReader.PreparedStep;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.json.JsonMapper;

class StructuredHandwritingIndexerTests {
    private static final String PROTOCOL = "HANDWRITING_METHODS_V1";
    private static final String SOURCE_ID = "source-1";
    private static final String FILE_REF = "file-1";
    private final Job job = new Job("event-1", 7, SOURCE_ID, 1, "SOURCE_VERSION_CREATED", 4, 2);
    private final RetrievalIndexStore indexes = mock(RetrievalIndexStore.class);
    private final HttpEmbeddingClient embedding = mock(HttpEmbeddingClient.class);
    private final HandwritingSourceReader handwriting = mock(HandwritingSourceReader.class);
    private final HandwritingIndexOcr ocr = mock(HandwritingIndexOcr.class);
    private final EmbeddingProperties embeddingProperties = new EmbeddingProperties();
    private final HandwritingMethodsProperties methodsProperties = new HandwritingMethodsProperties();
    private final JsonMapper json = JsonMapper.builder().build();
    private StructuredHandwritingIndexer indexer;

    @BeforeEach
    void setUp() {
        embeddingProperties.setEnabled(true);
        embeddingProperties.setModel("embedding-test");
        embeddingProperties.setDimensions(2);
        embeddingProperties.setBatchSize(1);
        methodsProperties.setEnabled(true);
        when(ocr.model()).thenReturn("ocr-test");
        when(indexes.beginIndexProtocol(job, PROTOCOL)).thenReturn(true);
        when(indexes.beginIndexStage(eq(job), any(StageKey.class))).thenReturn(new StageDecision(true, null));
        when(indexes.confirmIndexStage(eq(job), any(StageKey.class), any())).thenReturn(true);
        when(indexes.complete(eq(job), any(), eq("embedding-test"), eq(2))).thenReturn(true);
        when(handwriting.anchorDraft(any(PreparedStep.class), anyString())).thenAnswer(invocation -> {
            PreparedStep step = invocation.getArgument(0);
            String transcript = invocation.getArgument(1);
            return new AnchorDraft("HANDWRITING_STEP", step.ordinal(), transcript,
                    sha(transcript), step.locatorJson());
        });
        indexer = new StructuredHandwritingIndexer(indexes, embedding, embeddingProperties,
                methodsProperties, handwriting, ocr, json);
    }

    @Test
    void onlyNewUnanchoredSavedAnswerOptsInButPinnedJobKeepsItsProtocol() {
        SourceContent fresh = content(List.of());
        Job oldReindex = new Job("event-reindex", job.accountId(), job.sourceId(), job.version(),
                "SOURCE_REINDEX", job.fence(), job.indexRevision());
        assertThat(indexer.shouldHandle(job, fresh)).isTrue();
        assertThat(indexer.shouldHandle(job, content(List.of(existingAnchor())))).isFalse();
        assertThat(indexer.shouldHandle(oldReindex, fresh)).isFalse();
        methodsProperties.setEnabled(false);
        assertThat(indexer.shouldHandle(job, fresh)).isFalse();
        when(indexes.indexProtocol(job)).thenReturn(Optional.of(PROTOCOL));
        when(indexes.indexProtocol(oldReindex)).thenReturn(Optional.of(PROTOCOL));
        assertThat(indexer.shouldHandle(job, fresh)).isTrue();
        assertThat(indexer.shouldHandle(oldReindex, fresh)).isTrue();
        assertThat(indexer.shouldHandle(job, content(List.of(existingAnchor())))).isTrue();
        assertThatThrownBy(() -> indexer.process(job, content(List.of(existingAnchor()))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ocr, never()).recognize(any(ModelImage.class), anyInt(), anyInt(), anyInt());
        verifyNoInteractions(embedding);
    }

    @Test
    void twoStepsMakeTwoOcrStagesAndOneEmbeddingRequestPerBatchWithoutChangingAnchorText() {
        arrangeTwoSteps();
        indexer.process(job, content(List.of()));

        ArgumentCaptor<StageKey> keys = ArgumentCaptor.forClass(StageKey.class);
        verify(indexes, times(4)).beginIndexStage(eq(job), keys.capture());
        assertThat(keys.getAllValues()).extracting(StageKey::phase)
                .containsExactly("OCR_STEP", "OCR_STEP", "EMBED_BATCH", "EMBED_BATCH");
        assertThat(keys.getAllValues()).extracting(StageKey::ordinal).containsExactly(0, 1, 0, 1);
        assertThat(keys.getAllValues().get(0).inputSha256()).isEqualTo(step(0).imageSha256());
        assertThat(keys.getAllValues().get(0).sourceSha256()).isEqualTo(sha("source"));
        assertThat(keys.getAllValues().get(0).model()).isEqualTo("ocr-test");
        assertThat(keys.getAllValues().get(2).model()).isEqualTo("embedding-test");
        assertThat(keys.getAllValues().get(2).dimensions()).isEqualTo(2);
        verify(ocr).recognize(any(ModelImage.class), eq(0), eq(80), eq(40));
        verify(ocr).recognize(any(ModelImage.class), eq(1), eq(80), eq(40));
        verify(embedding).embedWithStats(List.of("配方法解题"));
        verify(embedding).embedWithStats(List.of("换元法求解"));
        ArgumentCaptor<List<Chunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(indexes).complete(eq(job), chunks.capture(), eq("embedding-test"), eq(2));
        assertThat(chunks.getValue()).hasSize(2);
        assertThat(chunks.getValue()).extracting(Chunk::text)
                .containsExactly("配方法解题", "换元法求解");
        assertThat(chunks.getValue().get(0).anchor().contentSha256()).isEqualTo(sha("配方法解题"));
        HandwritingMethodMetadata metadata = HandwritingMethodMetadata.parse(
                chunks.getValue().get(0).methodMetadataJson());
        metadata.validateBindings(job.accountId(), job.sourceId(), job.version(), job.indexRevision(),
                chunks.getValue().get(0).anchor().anchorId(), sha("配方法解题"), "配方法解题");
        assertThat(metadata.imageSha256()).isEqualTo(step(0).imageSha256());
        assertThat(metadata.result().methodCandidates().get(0).method()).isEqualTo("配方法");
    }

    @Test
    void confirmedStagesResumeWithoutAnotherOcrOrEmbeddingRequest() {
        arrangeTwoSteps();
        indexer.process(job, content(List.of()));
        ArgumentCaptor<StageKey> keys = ArgumentCaptor.forClass(StageKey.class);
        ArgumentCaptor<StageResult> results = ArgumentCaptor.forClass(StageResult.class);
        verify(indexes, times(4)).confirmIndexStage(eq(job), keys.capture(), results.capture());
        Map<String, StageResult> saved = new HashMap<>();
        for (int index = 0; index < keys.getAllValues().size(); index++) {
            StageKey key = keys.getAllValues().get(index);
            saved.put(key.phase() + ":" + key.ordinal(), results.getAllValues().get(index));
        }
        clearInvocations(ocr, embedding, indexes, handwriting);
        when(indexes.indexProtocol(job)).thenReturn(Optional.of(PROTOCOL));
        when(indexes.beginIndexStage(eq(job), any(StageKey.class))).thenAnswer(invocation -> {
            StageKey key = invocation.getArgument(1);
            return new StageDecision(false, saved.get(key.phase() + ":" + key.ordinal()));
        });

        indexer.process(job, content(List.of()));

        verify(ocr, never()).recognize(any(ModelImage.class), anyInt(), anyInt(), anyInt());
        verifyNoInteractions(embedding);
        verify(indexes, times(4)).beginIndexStage(eq(job), any(StageKey.class));
        verify(indexes, times(1)).complete(eq(job), any(), eq("embedding-test"), eq(2));
    }

    @Test
    void unknownIntentNeverCallsProviderOrPublishes() {
        arrangeTwoSteps();
        when(indexes.beginIndexStage(eq(job), any(StageKey.class)))
                .thenThrow(new StepExecutionException("OUTCOME_UNKNOWN"));

        assertThatThrownBy(() -> indexer.process(job, content(List.of())))
                .isInstanceOf(StepExecutionException.class);

        verify(ocr, never()).recognize(any(ModelImage.class), anyInt(), anyInt(), anyInt());
        verifyNoInteractions(embedding);
        verify(indexes, never()).complete(eq(job), any(), eq("embedding-test"), eq(2));
    }

    @Test
    void invalidStructuredResultDoesNotRetryThroughLiteralOcr() {
        arrangeTwoSteps();
        when(ocr.recognize(any(ModelImage.class), eq(0), eq(80), eq(40)))
                .thenReturn(result(0, "x^2+2x=(x+1)^2-1", "二次函数最值"));

        assertThatThrownBy(() -> indexer.process(job, content(List.of())))
                .isInstanceOf(StepExecutionException.class);

        verify(ocr, times(1)).recognize(any(ModelImage.class), anyInt(), anyInt(), anyInt());
        verify(handwriting, never()).read(anyString());
        verify(indexes).unknownIndexStage(eq(job), any(StageKey.class));
        verifyNoInteractions(embedding);
        verify(indexes, never()).complete(any(Job.class), any(), anyString(), anyInt());
    }

    @Test
    void emptySavedStrokesPublishEmptyIndexWithoutAnyProviderCall() {
        when(handwriting.prepare(FILE_REF)).thenReturn(List.of());

        indexer.process(job, content(List.of()));

        verify(indexes).complete(job, List.of(), "embedding-test", 2);
        verify(ocr, never()).recognize(any(ModelImage.class), anyInt(), anyInt(), anyInt());
        verifyNoInteractions(embedding);
        verify(indexes, never()).beginIndexStage(any(), any());
    }

    @Test
    void lateOcrConfirmationCannotContinueToEmbeddingOrPublication() {
        arrangeTwoSteps();
        when(indexes.confirmIndexStage(eq(job), any(StageKey.class), any())).thenReturn(false);

        assertThatThrownBy(() -> indexer.process(job, content(List.of())))
                .isInstanceOf(StepExecutionException.class);

        verify(ocr, times(1)).recognize(any(ModelImage.class), eq(0), eq(80), eq(40));
        verify(indexes).unknownIndexStage(eq(job), any(StageKey.class));
        verifyNoInteractions(embedding);
        verify(indexes, never()).complete(eq(job), any(), eq("embedding-test"), eq(2));
    }

    @Test
    void failedUnknownMarkerCannotHidePaidOcrOutcomeOrTriggerAnotherProviderCall() {
        arrangeTwoSteps();
        when(ocr.recognize(any(ModelImage.class), eq(0), eq(80), eq(40)))
                .thenThrow(new IllegalStateException("simulated provider failure"));
        doThrow(new IllegalStateException("simulated unknown marker storage failure"))
                .when(indexes).unknownIndexStage(eq(job), any(StageKey.class));

        assertThatThrownBy(() -> indexer.process(job, content(List.of())))
                .isInstanceOfSatisfying(StepExecutionException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo("OUTCOME_UNKNOWN");
                });

        verify(ocr, times(1)).recognize(any(ModelImage.class), eq(0), eq(80), eq(40));
        verify(ocr, never()).recognize(any(ModelImage.class), eq(1), eq(80), eq(40));
        verify(indexes).unknownIndexStage(eq(job), any(StageKey.class));
        verifyNoInteractions(embedding);
        verify(indexes, never()).complete(eq(job), any(), eq("embedding-test"), eq(2));
    }

    @Test
    void confirmedOcrSurvivesCrashBeforeEmbeddingIntent() {
        when(handwriting.prepare(FILE_REF)).thenReturn(List.of(step(0)));
        when(ocr.recognize(any(ModelImage.class), eq(0), eq(80), eq(40)))
                .thenReturn(result(0, "配方法解题", "配方法"));
        when(embedding.embedWithStats(List.of("配方法解题"))).thenReturn(embedded(1, 0));
        doThrow(new IllegalStateException("simulated crash after OCR confirmation"))
                .when(handwriting).anchorDraft(any(PreparedStep.class), anyString());

        assertThatThrownBy(() -> indexer.process(job, content(List.of())))
                .isInstanceOf(IllegalStateException.class);
        ArgumentCaptor<StageResult> confirmed = ArgumentCaptor.forClass(StageResult.class);
        verify(indexes).confirmIndexStage(eq(job), any(StageKey.class), confirmed.capture());
        clearInvocations(ocr, embedding, indexes, handwriting);
        when(indexes.indexProtocol(job)).thenReturn(Optional.of(PROTOCOL));
        when(indexes.beginIndexStage(eq(job), any(StageKey.class))).thenAnswer(invocation -> {
            StageKey key = invocation.getArgument(1);
            return "OCR_STEP".equals(key.phase())
                    ? new StageDecision(false, confirmed.getValue()) : new StageDecision(true, null);
        });
        doAnswer(invocation -> {
            PreparedStep prepared = invocation.getArgument(0);
            String transcript = invocation.getArgument(1);
            return new AnchorDraft("HANDWRITING_STEP", prepared.ordinal(), transcript,
                    sha(transcript), prepared.locatorJson());
        }).when(handwriting).anchorDraft(any(PreparedStep.class), anyString());

        indexer.process(job, content(List.of()));

        verify(ocr, never()).recognize(any(ModelImage.class), anyInt(), anyInt(), anyInt());
        verify(embedding).embedWithStats(List.of("配方法解题"));
        verify(indexes).complete(eq(job), any(), eq("embedding-test"), eq(2));
    }

    @Test
    void confirmedFirstBatchIsReusedWhenSecondBatchHasUnknownOutcome() {
        arrangeTwoSteps();
        when(embedding.embedWithStats(List.of("换元法求解")))
                .thenThrow(new EmbeddingException(EmbeddingException.Reason.UNAVAILABLE));
        assertThatThrownBy(() -> indexer.process(job, content(List.of())))
                .isInstanceOf(StepExecutionException.class);
        ArgumentCaptor<StageKey> keys = ArgumentCaptor.forClass(StageKey.class);
        ArgumentCaptor<StageResult> results = ArgumentCaptor.forClass(StageResult.class);
        verify(indexes, times(3)).confirmIndexStage(eq(job), keys.capture(), results.capture());
        Map<String, StageResult> saved = new HashMap<>();
        for (int index = 0; index < keys.getAllValues().size(); index++) {
            StageKey key = keys.getAllValues().get(index);
            saved.put(key.phase() + ":" + key.ordinal(), results.getAllValues().get(index));
        }
        assertThat(saved.keySet()).containsExactlyInAnyOrder("OCR_STEP:0", "OCR_STEP:1", "EMBED_BATCH:0");
        clearInvocations(ocr, embedding, indexes, handwriting);
        when(indexes.indexProtocol(job)).thenReturn(Optional.of(PROTOCOL));
        when(indexes.beginIndexStage(eq(job), any(StageKey.class))).thenAnswer(invocation -> {
            StageKey key = invocation.getArgument(1);
            StageResult confirmed = saved.get(key.phase() + ":" + key.ordinal());
            if (confirmed == null) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
            return new StageDecision(false, confirmed);
        });

        assertThatThrownBy(() -> indexer.process(job, content(List.of())))
                .isInstanceOf(StepExecutionException.class);

        verify(ocr, never()).recognize(any(ModelImage.class), anyInt(), anyInt(), anyInt());
        verifyNoInteractions(embedding);
        verify(indexes, never()).complete(eq(job), any(), eq("embedding-test"), eq(2));
    }

    @Test
    void storeBindingRefusalForChangedSourceModelDimensionOrRevisionStopsPaidCalls() {
        when(handwriting.prepare(FILE_REF)).thenReturn(List.of(step(0)));
        when(ocr.recognize(any(ModelImage.class), eq(0), eq(80), eq(40)))
                .thenReturn(result(0, "配方法解题", "配方法"));
        when(embedding.embedWithStats(List.of("配方法解题"))).thenReturn(embedded(1, 0));
        indexer.process(job, content(List.of()));
        ArgumentCaptor<StageResult> results = ArgumentCaptor.forClass(StageResult.class);
        verify(indexes, times(2)).confirmIndexStage(eq(job), any(StageKey.class), results.capture());
        StageResult savedOcr = results.getAllValues().get(0);
        clearInvocations(ocr, embedding, indexes, handwriting);
        when(indexes.beginIndexProtocol(any(Job.class), eq(PROTOCOL))).thenReturn(true);
        when(indexes.beginIndexStage(any(Job.class), any(StageKey.class))).thenAnswer(invocation -> {
            Job actualJob = invocation.getArgument(0);
            StageKey key = invocation.getArgument(1);
            if (actualJob.indexRevision() != 2 || !sha("source").equals(key.sourceSha256())
                    || "OCR_STEP".equals(key.phase()) && !"ocr-test".equals(key.model())
                    || "EMBED_BATCH".equals(key.phase())
                    && (!"embedding-test".equals(key.model()) || key.dimensions() != 2)) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
            return "OCR_STEP".equals(key.phase())
                    ? new StageDecision(false, savedOcr) : new StageDecision(true, null);
        });

        assertThatThrownBy(() -> indexer.process(job, contentWithDigest(sha("changed"))))
                .isInstanceOf(StepExecutionException.class);
        when(ocr.model()).thenReturn("ocr-changed");
        assertThatThrownBy(() -> indexer.process(job, content(List.of())))
                .isInstanceOf(StepExecutionException.class);
        when(ocr.model()).thenReturn("ocr-test");
        embeddingProperties.setModel("embedding-changed");
        assertThatThrownBy(() -> indexer.process(job, content(List.of())))
                .isInstanceOf(StepExecutionException.class);
        embeddingProperties.setModel("embedding-test");
        embeddingProperties.setDimensions(3);
        assertThatThrownBy(() -> indexer.process(job, content(List.of())))
                .isInstanceOf(StepExecutionException.class);
        embeddingProperties.setDimensions(2);
        Job changedRevision = new Job(job.eventId(), job.accountId(), job.sourceId(), job.version(),
                job.kind(), job.fence(), 3);
        assertThatThrownBy(() -> indexer.process(changedRevision, content(List.of())))
                .isInstanceOf(StepExecutionException.class);

        verify(ocr, never()).recognize(any(ModelImage.class), anyInt(), anyInt(), anyInt());
        verifyNoInteractions(embedding);
        verify(indexes, never()).complete(any(Job.class), any(), anyString(), anyInt());
    }

    private void arrangeTwoSteps() {
        List<PreparedStep> steps = List.of(step(0), step(1));
        when(handwriting.prepare(FILE_REF)).thenReturn(steps);
        when(ocr.recognize(any(ModelImage.class), eq(0), eq(80), eq(40)))
                .thenReturn(result(0, "配方法解题", "配方法"));
        when(ocr.recognize(any(ModelImage.class), eq(1), eq(80), eq(40)))
                .thenReturn(result(1, "换元法求解", "换元法"));
        when(embedding.embedWithStats(List.of("配方法解题"))).thenReturn(embedded(1, 0));
        when(embedding.embedWithStats(List.of("换元法求解"))).thenReturn(embedded(0, 1));
    }

    private static PreparedStep step(int ordinal) {
        byte[] png = ("test-png-" + ordinal).getBytes(StandardCharsets.UTF_8);
        return new PreparedStep(ordinal, new ModelImage("image/png", png, "step-" + ordinal),
                80, 40, sha(png), "{}");
    }

    private static Result result(int ordinal, String transcript, String method) {
        return new Result(HandwritingIndexOcr.SCHEMA_VERSION, "math-methods-v1", ordinal,
                transcript, "CLEAR", List.of(new MethodCandidate(method, 0, 3,
                        new Region(1, 2, 20, 10), "CANDIDATE", "VISIBLE_STEPS")));
    }

    private static HttpEmbeddingClient.EmbeddingResult embedded(double first, double second) {
        return new HttpEmbeddingClient.EmbeddingResult(List.of(new double[] {first, second}),
                1, 5L, 5L, "embedding-test");
    }

    private static SourceContent content(List<Anchor> anchors) {
        return content(anchors, sha("source"));
    }

    private static SourceContent contentWithDigest(String digest) {
        return content(List.of(), digest);
    }

    private static SourceContent content(List<Anchor> anchors, String digest) {
        Instant created = Instant.parse("2026-10-25T00:00:00Z");
        Source source = new Source(SOURCE_ID, 7, SourceType.SAVED_ANSWER, "question-1",
                "answer", "ACTIVE", 1, created);
        SourceVersion version = new SourceVersion(SOURCE_ID, 1, digest, "INDEX_PENDING",
                "DRAFT", FILE_REF, null, created, 2);
        return new SourceContent(source, version, anchors);
    }

    private static Anchor existingAnchor() {
        return new Anchor("anchor-1", SOURCE_ID, 1, "HANDWRITING_STEP", 0,
                sha("旧内容"), "{}", "旧内容");
    }

    private static String sha(String value) {
        return sha(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
