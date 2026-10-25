package com.betterlearning.server.agent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.betterlearning.server.agent.embedding.EmbeddingException;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingDecision;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingKey;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingResult;
import com.betterlearning.server.agent.retrieval.ranking.HybridRanker.Candidate;
import com.betterlearning.server.agent.retrieval.ranking.HybridRanker.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.RetrievalQuery;
import com.betterlearning.server.multiagent.StepExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PersonalEvidenceRetrieverTests {
    private final RetrievalIndexStore store = mock(RetrievalIndexStore.class);
    private final HttpEmbeddingClient embedding = mock(HttpEmbeddingClient.class);
    private final EmbeddingProperties properties = new EmbeddingProperties();
    private PersonalEvidenceRetriever retriever;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setModel("test-embedding");
        properties.setDimensions(2);
        when(store.retrievalStepId(7L, "run-1")).thenReturn("retrieve-step");
        retriever = new PersonalEvidenceRetriever(store, embedding, properties);
        when(store.beginQueryEmbedding(any())).thenReturn(new QueryEmbeddingDecision(true, null));
        when(embedding.embedWithStats(any())).thenReturn(new HttpEmbeddingClient.EmbeddingResult(
                List.of(new double[]{1, 0}), 1, 5L, 5L, "test-embedding"));
        when(store.confirmQueryEmbedding(any(), any(), eq(1), eq(5L))).thenReturn(true);
        when(store.readQueryEmbedding(any())).thenReturn(Optional.of(
                new QueryEmbeddingResult(new double[]{1, 0}, 1, 5L)));
        when(store.authorized(anyLong(), any(), anyLong(), any())).thenReturn(true);
    }

    @Test
    void disabledEmbeddingDoesNotRecordAnUnknownPaidIntent() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> retriever.retrieve(query("配方法", List.of("配方法"), 5)))
                .isInstanceOfSatisfying(EmbeddingException.class, error -> assertThat(error.reason())
                .isEqualTo(EmbeddingException.Reason.DISABLED));
        verify(store, never()).beginQueryEmbedding(any());
        verify(embedding, never()).embedWithStats(any());
    }

    @Test
    void boundsEvidenceAndPreservesRawAnchorTextDespiteSourceMetadata() {
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(candidate("answer", SourceType.SAVED_ANSWER, "q0", 0.99,
                "配方法旧草稿", "DRAFT"));
        candidates.add(candidate("completed", SourceType.SAVED_ANSWER, "q9", 0.985,
                "配方法已完成旧作答", "COMPLETED"));
        candidates.add(candidate("reply", SourceType.TUTOR_REPLY, "q1", 0.98, "配方法建议"));
        for (int index = 2; index < 7; index++) {
            candidates.add(candidate("note" + index, SourceType.NOTE, "q" + index,
                    0.80 - index * 0.01, "配方法笔记" + index));
        }
        when(store.candidates(eq(7L), anySet(), anySet(), any(), eq("test-embedding"), eq(2)))
                .thenReturn(candidates);

        var evidence = retriever.retrieve(query("配方法", List.of("配方法"), 20));

        assertThat(evidence).hasSize(5);
        assertThat(evidence).extracting(item -> item.claim())
                .contains("配方法旧草稿", "配方法已完成旧作答", "配方法建议");
        assertThat(evidence).allSatisfy(item -> assertThat(item.claim())
                .isEqualTo(candidates.stream().filter(candidate -> candidate.sourceId().equals(item.sourceId()))
                .findFirst().orElseThrow().text()));
        assertThat(evidence).allSatisfy(item -> assertThat(item.type()).isEqualTo("HISTORY"));
        assertThat(evidence).allSatisfy(item -> assertThat(item.stepId()).isEqualTo("retrieve-step"));
    }

    @Test
    void unseenMethodCannotUseSurfaceSimilarityOrHighVectorWithoutLiteralEvidence() {
        when(store.candidates(eq(7L), anySet(), anySet(), any(), eq("test-embedding"), eq(2)))
                .thenReturn(List.of(candidate("surface", SourceType.NOTE, "q1", 0.99,
                "二次函数配方法")));

        var evidence = retriever.retrieve(query(
                "二次函数与概率积分，不讨论配方法", List.of("概率积分"), 5));

        assertThat(evidence).isEmpty();
        verify(store).beginQueryEmbedding(any());
        verify(embedding).embedWithStats(any());
    }

    @Test
    void unseenMethodRequiresPositiveLiteralInRawTextRatherThanMetadata() {
        String label = "柯西施瓦茨不等式";
        String positive = "柯西施瓦茨不等式：把两组数分别看成向量。";
        Candidate correct = new Candidate("literal", 1, "literal-anchor", SourceType.NOTE,
                "q1", positive, Set.of(), 0.6, 3.0);
        Candidate metadataOnly = new Candidate("metadata-only", 1, "metadata-anchor", SourceType.NOTE,
                "q2", "这里只说明链式法则的求导步骤。", Set.of(label), 0.999, 3.0);
        Candidate negative = new Candidate("negative", 1, "negative-anchor", SourceType.NOTE,
                "q3", "不要使用柯西施瓦茨不等式，这里应使用配方法。", Set.of(), 0.99, 3.0);
        when(store.candidates(eq(7L), anySet(), anySet(), any(), eq("test-embedding"), eq(2)))
                .thenReturn(List.of(metadataOnly, negative, correct));

        var evidence = retriever.retrieve(query("证明当前不等式", List.of(" 柯西 施瓦茨 不等式 "), 5));

        assertThat(evidence).extracting(item -> item.claim()).containsExactly(positive);
        verify(store).candidates(eq(7L), anySet(), eq(Set.of(label)), any(), eq("test-embedding"), eq(2));
        verify(embedding).embedWithStats(List.of("证明当前不等式"));
    }

    @Test
    void labelCountAndLengthBoundsApplyBeforeDeduplicationAndPaidIntent() {
        List<String> repeated = java.util.Collections.nCopies(9, "柯西施瓦茨不等式");
        assertThatThrownBy(() -> retriever.retrieve(query("证明不等式", repeated, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retriever.retrieve(query("证明不等式", List.of("证".repeat(81)), 5)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(store, never()).beginQueryEmbedding(any());
        verify(embedding, never()).embedWithStats(any());
    }

    @Test
    void genericOrNegatedLabelsDoNotStartQueryEmbedding() {
        assertThat(retriever.retrieve(query("请给提示", List.of("未知方法", "不要使用洛必达法则"), 5)))
                .isEmpty();
        verify(store, never()).beginQueryEmbedding(any());
        verify(embedding, never()).embedWithStats(any());
    }

    @Test
    void differenceOfSquaresSolveLabelsRecallLegacyRawClaim() {
        assertRealMethodRecall("化简 x²−9", List.of("平方差公式", "因式分解"),
                Set.of("平方差公式", "因式分解"), "difference");
    }

    @Test
    void chainRuleSolveLabelsReachEmbeddingAndRecallLegacyRawClaim() {
        assertRealMethodRecall("求复合函数的导数", List.of("链式法则", "复合函数求导"),
                Set.of("链式法则"), "chain");
    }

    private void assertRealMethodRecall(String queryText, List<String> labels,
            Set<String> canonical, String expectedSource) {
        String difference = "平方差公式：a²−b²=(a−b)(a+b)。识别 9=3²。";
        String chain = "链式法则：先求外层导数，再乘内层导数。";
        Candidate differenceCandidate = new Candidate("difference", 1, "anchor-difference", SourceType.NOTE,
                "q1", difference, Set.of(), 0.767551, 0.0);
        Candidate chainCandidate = new Candidate("chain", 1, "anchor-chain", SourceType.NOTE,
                "q2", chain, Set.of(), 0.999, 0.0);
        when(store.candidates(eq(7L), anySet(), anySet(), any(), eq("test-embedding"), eq(2)))
                .thenReturn(List.of(differenceCandidate, chainCandidate));

        var evidence = retriever.retrieve(query(queryText, labels, 5));

        assertThat(evidence).extracting(item -> item.sourceId()).containsExactly(expectedSource);
        assertThat(evidence).extracting(item -> item.claim())
                .containsExactly("difference".equals(expectedSource) ? difference : chain);
        verify(store).beginQueryEmbedding(any());
        verify(embedding).embedWithStats(List.of(queryText));
        verify(store).candidates(eq(7L), anySet(), eq(canonical), any(), eq("test-embedding"), eq(2));
    }

    @Test
    void rechecksAuthorizationAndDropsDeletedSourceBeforeReturningEvidence() {
        Candidate deleted = candidate("deleted", SourceType.NOTE, "q1", 0.99, "配方法旧笔记");
        Candidate active = candidate("active", SourceType.NOTE, "q2", 0.5, "配方法新笔记");
        when(store.candidates(eq(7L), anySet(), anySet(), any(), eq("test-embedding"), eq(2)))
                .thenReturn(List.of(deleted, active));
        when(store.authorized(7L, "deleted", 1, "anchor")).thenReturn(false);

        var evidence = retriever.retrieve(query("配方法", List.of("配方法"), 5));

        assertThat(evidence).extracting(item -> item.sourceId()).containsExactly("active");
        verify(store).authorized(7L, "deleted", 1, "anchor");
        verify(store).authorized(7L, "active", 1, "anchor");
    }

    @Test
    void skipsOversizedAnchorsAndKeepsWholeClaimsWithinTotalBudget() {
        String longText = "配方法" + "x".repeat(3994) + "😀tail";
        when(store.candidates(eq(7L), anySet(), anySet(), any(), eq("test-embedding"), eq(2)))
                .thenReturn(List.of(candidate("long", SourceType.NOTE, "q1", 0.99, longText)));
        var evidence = retriever.retrieve(query("配方法", List.of("配方法"), 5));
        assertThat(evidence).isEmpty();

        String exactFit = "配方法" + "x".repeat(3995) + "😀";
        when(store.candidates(eq(7L), anySet(), anySet(), any(), eq("test-embedding"), eq(2)))
                .thenReturn(List.of(candidate("exact", SourceType.NOTE, "q1", 0.99, exactFit)));
        var exact = retriever.retrieve(query("配方法", List.of("配方法"), 5));
        assertThat(exact).hasSize(1);
        assertThat(exact.get(0).claim()).isEqualTo(exactFit);

        when(store.candidates(eq(7L), anySet(), anySet(), any(), eq("test-embedding"), eq(2)))
                .thenReturn(List.of(candidate("first", SourceType.NOTE, "q1", 0.99,
                "配方法" + "a".repeat(2400)), candidate("second", SourceType.NOTE, "q2", 0.98,
                "配方法" + "b".repeat(2400)), candidate("third", SourceType.NOTE, "q3", 0.97,
                "配方法短证据")));
        var multiple = retriever.retrieve(query("配方法", List.of("配方法"), 5));
        assertThat(multiple).hasSize(2);
        assertThat(multiple).extracting(item -> item.sourceId()).containsExactly("first", "third");
        assertThat(multiple).extracting(item -> item.claim())
                .containsExactly("配方法" + "a".repeat(2400), "配方法短证据");
        assertThat(multiple.stream().mapToInt(item -> item.claim().length()).sum()).isLessThanOrEqualTo(4000);
    }

    @Test
    void firstQueryPersistsIntentAndKnownUsageAroundOneEmbeddingCall() {
        retriever.retrieve(query("配方法", List.of("配方法"), 5));

        ArgumentCaptor<QueryEmbeddingKey> key = ArgumentCaptor.forClass(QueryEmbeddingKey.class);
        verify(store).beginQueryEmbedding(key.capture());
        assertThat(key.getValue().accountId()).isEqualTo(7L);
        assertThat(key.getValue().runId()).isEqualTo("run-1");
        assertThat(key.getValue().stepId()).isEqualTo("retrieve-step");
        assertThat(key.getValue().querySha256()).matches("[0-9a-f]{64}");
        assertThat(key.getValue().model()).isEqualTo("test-embedding");
        assertThat(key.getValue().dimensions()).isEqualTo(2);
        verify(embedding, times(1)).embedWithStats(List.of("配方法"));
        verify(store).confirmQueryEmbedding(eq(key.getValue()), any(), eq(1), eq(5L));
    }

    @Test
    void confirmedQueryVectorIsReusedWithoutAnotherProviderCall() {
        when(store.beginQueryEmbedding(any())).thenReturn(new QueryEmbeddingDecision(false,
                new QueryEmbeddingResult(new double[]{1, 0}, 1, 5L)));

        retriever.retrieve(query("配方法", List.of("配方法"), 5));

        verify(embedding, never()).embedWithStats(any());
        verify(store, never()).confirmQueryEmbedding(any(), any(), anyInt(), any());
    }

    @Test
    void unknownQueryIntentStopsBeforeProviderCall() {
        when(store.beginQueryEmbedding(any())).thenThrow(new StepExecutionException("OUTCOME_UNKNOWN"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> retriever.retrieve(query("配方法", List.of("配方法"), 5)))
                .isInstanceOf(StepExecutionException.class).hasMessage("OUTCOME_UNKNOWN");

        verify(embedding, never()).embedWithStats(any());
    }

    @Test
    void unconfirmedProviderResultIsUnknownAndNeverPublished() {
        when(store.confirmQueryEmbedding(any(), any(), eq(1), eq(5L))).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> retriever.retrieve(query("配方法", List.of("配方法"), 5)))
                .isInstanceOf(StepExecutionException.class).hasMessage("OUTCOME_UNKNOWN");

        verify(embedding, times(1)).embedWithStats(List.of("配方法"));
        verify(store, never()).candidates(anyLong(), anySet(), anySet(), any(), any(), eq(2));
    }

    private RetrievalQuery query(String text, List<String> methods, int limit) {
        return new RetrievalQuery(7, "run-1", "question-1", text, methods, limit);
    }

    private Candidate candidate(String id, SourceType type, String questionId,
            double vectorScore, String text) {
        return candidate(id, type, questionId, vectorScore, text, null);
    }

    private Candidate candidate(String id, SourceType type, String questionId,
            double vectorScore, String text, String answerState) {
        return new Candidate(id, 1, "anchor", type, questionId, text, Set.of("配方法"),
                vectorScore, 0.5, answerState);
    }
}
