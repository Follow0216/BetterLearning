package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Job;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingKey;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageKey;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.StageResult;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.multiagent.MultiAgentModels.Source;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceVersion;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.retrieval.mapper.RetrievalMapper;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class MybatisRetrievalIndexStoreTests {
    private final RetrievalMapper mapper = mock(RetrievalMapper.class);
    private final SourceStore sources = mock(SourceStore.class);
    private final MybatisRetrievalIndexStore store = new MybatisRetrievalIndexStore(
            mapper, sources, JsonMapper.builder().build());

    @Test
    void expiredJobWithoutExternalIntentCanResume() {
        when(mapper.claim()).thenReturn(row("NOT_SENT"));

        assertThat(store.claim()).contains(new Job("event-1", 7L, "source-1", 2L,
                "SOURCE_REINDEX", 4L, 3L, 2));

        verify(mapper).prepareJobs();
        verifyNoInteractions(sources);
    }

    @Test
    void expiredIntentFailsUnknownInsteadOfResendingPaidCall() {
        when(mapper.claim()).thenReturn(row("CALL_INTENT"));
        when(mapper.lockAccount(7L)).thenReturn(7L);
        when(mapper.lockJob("event-1", 4L)).thenReturn("event-1");
        when(mapper.failure("event-1", 4L, "OUTCOME_UNKNOWN")).thenReturn(1);

        assertThat(store.claim()).isEmpty();

        verify(sources).markIndex(7L, "source-1", 2L, 3L, "FAILED");
        verify(mapper).failure("event-1", 4L, "OUTCOME_UNKNOWN");
        verify(mapper).eventStatus("event-1", 7L, "FAILED");
    }

    @Test
    void confirmedCallWithoutCommittedOutputAlsoRequiresExplicitReindex() {
        when(mapper.claim()).thenReturn(row("CONFIRMED"));
        when(mapper.lockAccount(7L)).thenReturn(7L);
        when(mapper.lockJob("event-1", 4L)).thenReturn("event-1");
        when(mapper.failure("event-1", 4L, "OUTCOME_UNKNOWN")).thenReturn(1);

        assertThat(store.claim()).isEmpty();

        verify(mapper).failure("event-1", 4L, "OUTCOME_UNKNOWN");
        verify(mapper).eventStatus("event-1", 7L, "FAILED");
    }

    @Test
    void externalCallTransitionsAreFenced() {
        Job job = new Job("event-1", 7L, "source-1", 2L, "SOURCE_REINDEX", 4L, 3L, 2);
        when(mapper.beginExternalCall("event-1", 4L)).thenReturn(1);
        when(mapper.confirmExternalCall("event-1", 4L)).thenReturn(1);

        assertThat(store.beginExternalCall(job)).isTrue();
        assertThat(store.confirmExternalCall(job)).isTrue();

        verify(mapper).beginExternalCall("event-1", 4L);
        verify(mapper).confirmExternalCall("event-1", 4L);
    }

    @Test
    void staleFailureFenceCannotMarkCurrentSourceFailed() {
        Job job = new Job("event-1", 7L, "source-1", 2L, "SOURCE_REINDEX", 4L, 3L, 2);

        when(mapper.lockAccount(7L)).thenReturn(7L);
        store.fail(job, "OUTCOME_UNKNOWN");

        verify(mapper).lockJob("event-1", 4L);
        verifyNoInteractions(sources);
    }

    @Test
    void confirmedStructuredStageCanBeReadAfterNewFenceWithoutExternalCall() {
        Job job = job();
        StageKey key = stageKey();
        stageSource(job);
        when(mapper.indexProtocol("event-1", 4L)).thenReturn("HANDWRITING_METHODS_V1");
        when(mapper.indexStage("event-1", "OCR_STEP", 0)).thenReturn(stageRow(key, "CONFIRMED"));

        var decision = store.beginIndexStage(job, key);

        assertThat(decision.shouldCall()).isFalse();
        assertThat(decision.confirmed().outputJson()).isEqualTo("{}");
        verify(mapper, org.mockito.Mockito.never()).insertIndexStage(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void unconfirmedStructuredStageCannotBeReplayed() {
        Job job = job();
        StageKey key = stageKey();
        stageSource(job);
        when(mapper.indexProtocol("event-1", 4L)).thenReturn("HANDWRITING_METHODS_V1");
        when(mapper.indexStage("event-1", "OCR_STEP", 0)).thenReturn(stageRow(key, "CALL_INTENT"));

        assertThatThrownBy(() -> store.beginIndexStage(job, key))
                .isInstanceOf(StepExecutionException.class).hasMessage("OUTCOME_UNKNOWN");
        verify(mapper, org.mockito.Mockito.never()).insertIndexStage(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void changedImageDigestRejectsConfirmedStageReuse() {
        Job job = job();
        StageKey key = stageKey();
        stageSource(job);
        when(mapper.indexProtocol("event-1", 4L)).thenReturn("HANDWRITING_METHODS_V1");
        when(mapper.indexStage("event-1", "OCR_STEP", 0)).thenReturn(stageRow(key, "CONFIRMED"));
        StageKey changed = new StageKey("OCR_STEP", 0, key.sourceSha256(), key.fileRef(),
                "c".repeat(64), key.model(), 0, key.schemaVersion());

        assertThatThrownBy(() -> store.beginIndexStage(job, changed))
                .isInstanceOf(StepExecutionException.class).hasMessage("OUTCOME_UNKNOWN");
    }

    @Test
    void unavailableSourceRejectsBeforeEnteringTransactionalSourceRead() {
        Job job = job();
        when(mapper.lockAccount(job.accountId())).thenReturn(job.accountId());
        when(mapper.lockJob(job.eventId(), job.fence())).thenReturn(job.eventId());
        when(mapper.indexProtocol(job.eventId(), job.fence())).thenReturn("HANDWRITING_METHODS_V1");

        assertThat(store.confirmIndexStage(job, stageKey(), new StageResult("{}", sha("{}"), 1, null)))
                .isFalse();
        assertThat(store.beginIndexProtocol(job, "HANDWRITING_METHODS_V1")).isFalse();
        assertThatThrownBy(() -> store.beginIndexStage(job, stageKey()))
                .isInstanceOf(StepExecutionException.class).hasMessage("OUTCOME_UNKNOWN");
        verifyNoInteractions(sources);
        verify(mapper, org.mockito.Mockito.never()).confirmIndexStage(org.mockito.ArgumentMatchers.anyMap());
        verify(mapper, org.mockito.Mockito.never()).insertIndexStage(org.mockito.ArgumentMatchers.anyMap());
        verify(mapper, org.mockito.Mockito.never()).usage(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void unexpectedTransactionalSourceFailureIsNotSwallowedAsFalse() {
        Job job = job();
        stageSource(job);
        when(mapper.indexProtocol(job.eventId(), job.fence())).thenReturn("HANDWRITING_METHODS_V1");
        BusinessException failure = new BusinessException(ErrorCode.NOT_FOUND);
        when(sources.get(job.accountId(), job.sourceId(), job.version())).thenThrow(failure);

        assertThatThrownBy(() -> store.confirmIndexStage(job, stageKey(),
                new StageResult("{}", sha("{}"), 1, null))).isSameAs(failure);
        verify(mapper, org.mockito.Mockito.never()).confirmIndexStage(org.mockito.ArgumentMatchers.anyMap());
        verify(mapper, org.mockito.Mockito.never()).usage(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void confirmingStageRecordsKnownUsageOnce() {
        Job job = job();
        StageKey key = stageKey();
        stageSource(job);
        when(mapper.indexProtocol("event-1", 4L)).thenReturn("HANDWRITING_METHODS_V1");
        when(mapper.confirmIndexStage(org.mockito.ArgumentMatchers.anyMap())).thenReturn(1);
        when(mapper.usage(org.mockito.ArgumentMatchers.anyMap())).thenReturn(1);

        assertThat(store.confirmIndexStage(job, key, new StageResult("{}", sha("{}"), 1, 9L))).isTrue();
        verify(mapper).confirmIndexStage(org.mockito.ArgumentMatchers.argThat(stage ->
                Long.valueOf(9L).equals(stage.get("totalTokens"))));
        verify(mapper).usage(org.mockito.ArgumentMatchers.argThat(usage ->
                Integer.valueOf(1).equals(usage.get("ocrCalls"))
                && Integer.valueOf(0).equals(usage.get("embeddingRequests"))
                && usage.get("tokens") == null));
    }

    @Test
    void embeddingStageCannotConfirmPayloadWithoutMatchingOrderedInputs() {
        StageKey embedding = new StageKey("EMBED_BATCH", 0, "a".repeat(64), "file-1",
                "b".repeat(64), "embed-model", 2, IndexStagePayloads.EMBEDDING_SCHEMA);

        assertThatThrownBy(() -> store.confirmIndexStage(job(), embedding,
                new StageResult("{}", sha("{}"), 1, 7L)))
                .isInstanceOf(StepExecutionException.class).hasMessage("OUTCOME_UNKNOWN");
        verifyNoInteractions(mapper, sources);
    }

    @Test
    void expiredStructuredIntentFailsUnknownAtClaim() {
        Map<String, Object> claimed = row("NOT_SENT");
        claimed.put("index_protocol", "HANDWRITING_METHODS_V1");
        when(mapper.claim()).thenReturn(claimed);
        when(mapper.unconfirmedStageCount("event-1")).thenReturn(1);
        when(mapper.lockAccount(7L)).thenReturn(7L);
        when(mapper.lockJob("event-1", 4L)).thenReturn("event-1");
        when(mapper.failure("event-1", 4L, "OUTCOME_UNKNOWN")).thenReturn(1);

        assertThat(store.claim()).isEmpty();

        verify(mapper).failure("event-1", 4L, "OUTCOME_UNKNOWN");
        verify(sources).markIndex(7L, "source-1", 2L, 3L, "FAILED");
    }

    @Test
    void firstQueryInsertGetsOnlyExternalCallRight() {
        when(mapper.insertQueryIntent(org.mockito.ArgumentMatchers.anyMap())).thenReturn(1);

        var decision = store.beginQueryEmbedding(queryKey());

        assertThat(decision.shouldCall()).isTrue();
        assertThat(decision.confirmed()).isNull();
    }

    @Test
    void existingQueryIntentHasUnknownOutcomeAndCannotCallAgain() {
        when(mapper.queryEmbedding("step-1")).thenReturn(queryRow("CALL_INTENT", "[1,0]"));

        assertThatThrownBy(() -> store.beginQueryEmbedding(queryKey()))
                .isInstanceOf(StepExecutionException.class).hasMessage("OUTCOME_UNKNOWN");
    }

    @Test
    void confirmedQueryCanReadVectorAndKnownUsage() {
        when(mapper.queryEmbedding("step-1")).thenReturn(queryRow("CONFIRMED", "[1,0]"));

        var decision = store.beginQueryEmbedding(queryKey());

        assertThat(decision.shouldCall()).isFalse();
        assertThat(decision.confirmed().vector()).containsExactly(1.0, 0.0);
        assertThat(decision.confirmed().requestCount()).isEqualTo(1);
        assertThat(decision.confirmed().totalTokens()).isEqualTo(5L);
    }

    @Test
    void sameStepWithDifferentQueryCannotReuseVector() {
        Map<String, Object> existing = queryRow("CONFIRMED", "[1,0]");
        existing.put("query_sha256", "b".repeat(64));
        when(mapper.queryEmbedding("step-1")).thenReturn(existing);

        assertThatThrownBy(() -> store.beginQueryEmbedding(queryKey()))
                .isInstanceOf(StepExecutionException.class).hasMessage("OUTCOME_UNKNOWN");
    }

    private static QueryEmbeddingKey queryKey() {
        return new QueryEmbeddingKey(7L, "run-1", "step-1", "a".repeat(64), "model", 2);
    }

    private static Job job() {
        return new Job("event-1", 7L, "source-1", 2L, "SOURCE_REINDEX", 4L, 3L, 2);
    }

    private static StageKey stageKey() {
        return new StageKey("OCR_STEP", 0, "a".repeat(64), "file-1", "b".repeat(64),
                "ocr-model", 0, "handwriting-ocr-v1:math-methods-v1");
    }

    private void stageSource(Job job) {
        when(mapper.lockJob(job.eventId(), job.fence())).thenReturn(job.eventId());
        when(mapper.lockAccount(job.accountId())).thenReturn(job.accountId());
        when(mapper.currentIndexEvent(job.eventId(), job.accountId(), job.sourceId(),
                job.version(), job.indexRevision())).thenReturn(1);
        when(sources.get(job.accountId(), job.sourceId(), job.version())).thenReturn(new SourceContent(
                new Source(job.sourceId(), job.accountId(), SourceType.SAVED_ANSWER,
                "question-1", "answer", "ACTIVE", job.version(), null),
                new SourceVersion(job.sourceId(), job.version(), "a".repeat(64), "INDEX_PENDING",
                "DRAFT", "file-1", null, null, job.indexRevision()), List.of()));
    }

    private static Map<String, Object> stageRow(StageKey key, String state) {
        Map<String, Object> row = new HashMap<>();
        row.put("event_id", "event-1");
        row.put("phase", key.phase());
        row.put("ordinal", key.ordinal());
        row.put("account_id", 7L);
        row.put("source_id", "source-1");
        row.put("source_version", 2L);
        row.put("index_revision", 3L);
        row.put("source_sha256", key.sourceSha256());
        row.put("file_ref", key.fileRef());
        row.put("input_sha256", key.inputSha256());
        row.put("model", key.model());
        row.put("dimensions", key.dimensions());
        row.put("schema_version", key.schemaVersion());
        row.put("call_state", state);
        row.put("output_json", "{}");
        row.put("output_sha256", sha("{}"));
        row.put("request_count", 1);
        return row;
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Object> queryRow(String state, String vector) {
        Map<String, Object> row = new HashMap<>();
        row.put("step_id", "step-1");
        row.put("account_id", 7L);
        row.put("run_id", "run-1");
        row.put("query_sha256", "a".repeat(64));
        row.put("embedding_model", "model");
        row.put("dimensions", 2);
        row.put("call_state", state);
        row.put("embedding", vector);
        row.put("embedding_requests", 1);
        row.put("embedding_tokens", 5L);
        return row;
    }

    private static Map<String, Object> row(String callState) {
        Map<String, Object> row = new HashMap<>();
        row.put("event_id", "event-1");
        row.put("account_id", 7L);
        row.put("aggregate_id", "source-1");
        row.put("aggregate_version", 2L);
        row.put("kind", "SOURCE_REINDEX");
        row.put("fence", 4L);
        row.put("index_revision", 3L);
        row.put("attempts", 2);
        row.put("call_state", callState);
        return row;
    }
}
