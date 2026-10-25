package com.betterlearning.server.agent.retrieval.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HybridRankerTests {
    private final HybridRanker ranker = new HybridRanker();

    @Test
    void chineseMethodMatchesDifferentQuestionAndOutranksSurfaceSimilarity() {
        HybridRanker.Query query = new HybridRanker.Query("二次函数如何配方？", Set.of());
        HybridRanker.Candidate method = candidate("a", "q1", "用配方法完成平方", Set.of(),
                0.1, 0.1);
        HybridRanker.Candidate surface = candidate("b", "q2", "二次函数图像的面积", Set.of(),
                0.99, 0.99);
        HybridRanker.Ranking result = ranker.rank(query, List.of(surface, method),
                HybridRanker.Limits.defaults(), null);
        assertEquals("a", result.evidence().get(0).candidate().sourceId());
        assertEquals(Set.of("配方法"), result.evidence().get(0).matchedMethods());
    }

    @Test
    void noControlledMatchWithoutCalibratedThresholdReturnsEmpty() {
        HybridRanker.Query query = new HybridRanker.Query("求解", Set.of());
        HybridRanker.Candidate candidate = candidate("a", "q1", "普通文字", Set.of(),
                0.99, 0.99);
        assertTrue(ranker.rank(query, List.of(candidate), HybridRanker.Limits.defaults(), null)
                .evidence().isEmpty());
        assertEquals(1, ranker.rank(query, List.of(candidate), HybridRanker.Limits.defaults(),
                0.95).evidence().size());
        assertTrue(ranker.rank(query, List.of(candidate), HybridRanker.Limits.defaults(),
                1.0).evidence().isEmpty());
    }

    @Test
    void explicitMethodRejectsSameSurfaceWithWrongMethod() {
        HybridRanker.Query query = new HybridRanker.Query(
                "二次函数图像的面积，使用换元法", Set.of("换元法"));
        HybridRanker.Candidate sameSurface = candidate("surface", "q1",
                "二次函数图像的面积可用配方法", Set.of("配方法"), 0.99, 0.99);
        HybridRanker.Candidate sameMethod = candidate("method", "q2",
                "根式方程使用换元法", Set.of("换元法"), 0.10, 0.10);
        HybridRanker.Ranking result = ranker.rank(query, List.of(sameSurface, sameMethod),
                HybridRanker.Limits.defaults(), 0.9);
        assertEquals(List.of("method"), result.evidence().stream()
                .map(item -> item.candidate().sourceId()).toList());
    }

    @Test
    void explicitUnknownMethodDoesNotFallBackToSurfaceOrVector() {
        HybridRanker.Query query = new HybridRanker.Query(
                "二次函数与随机变量联合分布求概率，不讨论配方法", Set.of("概率积分"));
        HybridRanker.Candidate surface = candidate("p1", "q1",
                "二次函数配方法步骤", Set.of("配方法"), 0.99, 0.99);
        assertTrue(ranker.rank(query, List.of(surface), HybridRanker.Limits.defaults(),
                0.1).evidence().isEmpty());
    }

    @Test
    void p3SolveLinearEquationLabelMatchesControlledCandidate() {
        // DefaultRolePipeline passes SOLVE methodTags through; P3's fixture emits this exact label.
        HybridRanker.Query query = new HybridRanker.Query("方程如何移项求 x？", Set.of("一元一次方程"));
        HybridRanker.Candidate correct = candidate("linear", "q1",
                "一元一次方程先移项，再合并同类项。", Set.of("一元一次方程"), 0.1, 0.1);
        HybridRanker.Candidate wrong = candidate("quadratic", "q2",
                "方程用配方法处理二次项。", Set.of("配方法"), 0.99, 0.99);
        assertEquals(Set.of("一元一次方程"), ChineseMathTerms.explicitMethods(query.methodTags()));
        assertEquals(Set.of("一元一次方程"), ChineseMathTerms.terms(correct.text()));
        assertEquals(List.of("linear"), ranker.rank(query, List.of(wrong, correct),
                HybridRanker.Limits.defaults(), null).evidence().stream()
                .map(item -> item.candidate().sourceId()).toList());
        assertTrue(ranker.rank(new HybridRanker.Query(query.text(), Set.of("未知解法标签")),
                List.of(correct), HybridRanker.Limits.defaults(), null).evidence().isEmpty());
    }

    @Test
    void negatedMethodWithoutLabelsIsNotPositiveEvidence() {
        HybridRanker.Query query = new HybridRanker.Query("二次函数不讨论配方法", Set.of());
        HybridRanker.Candidate excluded = candidate("p1", "q1",
                "二次函数用配方法求解", Set.of("配方法"), 0.99, 0.99);
        assertEquals(Set.of(), ChineseMathTerms.methodsInText(query.text()));
        assertEquals(Set.of("配方法"), ChineseMathTerms.negatedMethods(query.text()));
        assertTrue(ranker.rank(query, List.of(excluded), HybridRanker.Limits.defaults(),
                0.1).evidence().isEmpty());
    }

    @Test
    void excludesSnapshotDeduplicatesQuestionAndCapsOutput() {
        HybridRanker.Query query = new HybridRanker.Query("", Set.of("换元法"));
        List<HybridRanker.Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            candidates.add(candidate("s" + index, index < 2 ? "same" : "q" + index,
                    "换元法步骤" + index, Set.of(), 0.8 - index * 0.01, 0.8));
        }
        candidates.add(new HybridRanker.Candidate("snapshot", 1, "anchor",
                HybridRanker.SourceType.TUTOR_SNAPSHOT, "unique", "换元法",
                Set.of(), 1.0, 1.0));
        HybridRanker.Ranking result = ranker.rank(query, candidates,
                HybridRanker.Limits.defaults(), null);
        assertEquals(5, result.evidence().size());
        assertEquals(3, result.citationSuggestions().size());
        assertEquals(1, result.evidence().stream().filter(item ->
                "same".equals(item.candidate().questionId())).count());
        assertFalse(result.evidence().stream().anyMatch(item ->
                item.candidate().type() == HybridRanker.SourceType.TUTOR_SNAPSHOT));
    }

    @Test
    void unicodeTruncationKeepsWholeSurrogatePair() {
        HybridRanker.Candidate candidate = candidate("a", "q1", "配方😀后续", Set.of(), 0.1, 0.1);
        HybridRanker.Query query = new HybridRanker.Query("配方", Set.of());
        HybridRanker.Ranking shortResult = ranker.rank(query, List.of(candidate),
                new HybridRanker.Limits(5, 3, 3), null);
        assertEquals("配方", shortResult.evidence().get(0).excerpt());
        HybridRanker.Ranking completePair = ranker.rank(query, List.of(candidate),
                new HybridRanker.Limits(5, 3, 4), null);
        assertEquals("配方😀", completePair.evidence().get(0).excerpt());
    }

    @Test
    void rejectsInvalidLimitsAndScores() {
        assertThrows(IllegalArgumentException.class, () -> new HybridRanker.Limits(6, 3, 100));
        assertThrows(IllegalArgumentException.class, () -> candidate("a", "q1", "配方",
                Set.of(), Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> ranker.rank(
                new HybridRanker.Query("", Set.of()), List.of(), HybridRanker.Limits.defaults(),
                Double.NaN));
    }

    @Test
    void candidateRetainsAnswerStateAndNineArgumentConstructor() {
        HybridRanker.Candidate legacy = candidate("legacy", "q1", "配方法", Set.of(), 0.1, 0.2);
        HybridRanker.Candidate completed = new HybridRanker.Candidate("answer", 1, "anchor",
                HybridRanker.SourceType.SAVED_ANSWER, "q2", "配方法作答", Set.of("配方法"),
                0.2, 0.3, "COMPLETED");
        assertEquals(null, legacy.answerState());
        assertEquals("COMPLETED", completed.answerState());
        assertThrows(IllegalArgumentException.class, () -> new HybridRanker.Candidate("bad", 1,
                "anchor", HybridRanker.SourceType.SAVED_ANSWER, "q3", "配方法", Set.of(),
                0.1, 0.1, "UNKNOWN"));
    }

    @Test
    void historicalAnswerAndTutorReplyFromSameQuestionDeduplicate() {
        HybridRanker.Candidate answer = new HybridRanker.Candidate("answer", 1, "anchor-a",
                HybridRanker.SourceType.SAVED_ANSWER, "question-7", "配方法作答", Set.of("配方法"),
                0.9, 0.9, "DRAFT");
        HybridRanker.Candidate reply = new HybridRanker.Candidate("reply", 1, "anchor-r",
                HybridRanker.SourceType.TUTOR_REPLY, "question-7", "配方法辅导", Set.of("配方法"),
                0.8, 0.8);
        HybridRanker.Ranking result = ranker.rank(new HybridRanker.Query("配方法", Set.of("配方法")),
                List.of(answer, reply), HybridRanker.Limits.defaults(), null);
        assertEquals(1, result.evidence().size());
        assertEquals("question-7", result.evidence().get(0).candidate().questionId());
    }

    private HybridRanker.Candidate candidate(String sourceId, String questionId, String text,
            Set<String> tags, double vectorScore, double lexicalScore) {
        return new HybridRanker.Candidate(sourceId, 1, "anchor", HybridRanker.SourceType.NOTE,
                questionId, text, tags, vectorScore, lexicalScore);
    }
}
