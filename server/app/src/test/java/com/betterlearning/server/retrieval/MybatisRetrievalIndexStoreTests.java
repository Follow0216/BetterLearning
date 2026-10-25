package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Job;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingKey;
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

        store.fail(job, "OUTCOME_UNKNOWN");

        verify(mapper).lockJob("event-1", 4L);
        verifyNoInteractions(sources);
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
