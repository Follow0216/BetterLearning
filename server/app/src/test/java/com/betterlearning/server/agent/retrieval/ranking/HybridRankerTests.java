package com.betterlearning.server.agent.retrieval.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    void validatedHandwritingInferenceOnlySupportsExplicitActualMethodQueries() {
        HybridRanker.Candidate formula = new HybridRanker.Candidate("formula", 1, "anchor",
                HybridRanker.SourceType.SAVED_ANSWER, "q1", "x²+2x=(x+1)²-1", Set.of(),
                0.1, 0.0, "DRAFT", Set.of("配方法"));
        HybridRanker.Candidate wrongMethod = new HybridRanker.Candidate("wrong", 1, "anchor2",
                HybridRanker.SourceType.SAVED_ANSWER, "q2", "x²+2x=(x+1)²-1", Set.of(),
                0.99, 0.99, "COMPLETED", Set.of("换元法"));
        assertEquals(List.of("formula"), ranker.rank(
                new HybridRanker.Query("求函数最值", Set.of("配方")),
                List.of(wrongMethod, formula), HybridRanker.Limits.defaults(), null)
                .evidence().stream().map(item -> item.candidate().sourceId()).toList());
        assertTrue(ranker.rank(new HybridRanker.Query("求函数最值", Set.of("二次函数最值")),
                List.of(formula), HybridRanker.Limits.defaults(), null).evidence().isEmpty());
        assertTrue(ranker.rank(new HybridRanker.Query("求函数最值", Set.of("柯西施瓦茨不等式")),
                List.of(formula), HybridRanker.Limits.defaults(), null).evidence().isEmpty());
        assertTrue(ranker.rank(new HybridRanker.Query("求函数最值", Set.of()),
                List.of(formula), HybridRanker.Limits.defaults(), null).evidence().isEmpty());
    }

    @Test
    void inferredMethodCannotOverrideNegatedOcrOrNonAnswerSource() {
        HybridRanker.Candidate denied = new HybridRanker.Candidate("denied", 1, "anchor",
                HybridRanker.SourceType.SAVED_ANSWER, "q1", "这里不使用配方法。", Set.of(),
                0.99, 0.99, "DRAFT", Set.of("配方法"));
        assertTrue(ranker.rank(new HybridRanker.Query("二次函数", Set.of("配方法")),
                List.of(denied), HybridRanker.Limits.defaults(), null).evidence().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new HybridRanker.Candidate(
                "note", 1, "anchor", HybridRanker.SourceType.NOTE, null, "公式", Set.of(),
                0.5, 0.5, null, Set.of("配方法")));
        assertThrows(IllegalArgumentException.class, () -> new HybridRanker.Candidate(
                "unknown", 1, "anchor", HybridRanker.SourceType.SAVED_ANSWER, "q2", "公式",
                Set.of(), 0.5, 0.5, "DRAFT", Set.of("柯西施瓦茨不等式")));
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
    void actualDifferenceOfSquaresLabelsMatchUnlabeledHistoricalTextOnly() {
        HybridRanker.Query query = new HybridRanker.Query("识别 a²−b² 并分解，9=3²",
                Set.of("平方差公式", "因式分解"));
        HybridRanker.Candidate historical = candidate("difference", "q1",
                "平方差公式：a²−b²=(a−b)(a+b)。识别 9=3²。", Set.of(), 0.767551, 0.0);
        HybridRanker.Candidate sameSurfaceWrongMethod = candidate("wrong", "q2",
                "a²−b² 与 9=3² 的题面可先考虑配方法。", Set.of("配方法"), 0.99, 0.99);
        HybridRanker.Candidate duplicateQuestion = candidate("duplicate", "q1",
                "平方差公式还可用于相近题目。", Set.of(), 0.10, 0.0);
        assertEquals(Set.of("平方差公式", "因式分解"),
                ChineseMathTerms.explicitMethods(query.methodTags()));
        assertEquals(Set.of("平方差公式"), ChineseMathTerms.methodsInText(historical.text()));
        HybridRanker.Ranking result = ranker.rank(query,
                List.of(sameSurfaceWrongMethod, historical, duplicateQuestion),
                HybridRanker.Limits.defaults(), null);
        assertEquals(List.of("difference"), result.evidence().stream()
                .map(item -> item.candidate().sourceId()).toList());
        assertEquals(Set.of("平方差公式"), result.evidence().get(0).matchedMethods());
    }

    @Test
    void actualChainRuleLabelsCanonicalizeAndRejectSurfaceOnlyCandidate() {
        HybridRanker.Query query = new HybridRanker.Query("复合函数的导数如何求？",
                Set.of("链式法则", "复合函数求导"));
        HybridRanker.Candidate historical = candidate("chain", "q1",
                "链式法则：先求外层导数，再乘内层导数。", Set.of(), 0.36, 0.0);
        HybridRanker.Candidate sameSurfaceWrongMethod = candidate("wrong", "q2",
                "复合函数的导数题面，使用待定系数法。", Set.of("待定系数法"), 0.99, 0.99);
        assertEquals(Set.of("链式法则"), ChineseMathTerms.explicitMethods(query.methodTags()));
        assertEquals(Set.of("链式法则"), ChineseMathTerms.explicitMethods(
                Set.of("链式求导")));
        assertEquals(Set.of("链式法则"), ChineseMathTerms.methodsInText(historical.text()));
        assertEquals(List.of("chain"), ranker.rank(query,
                List.of(sameSurfaceWrongMethod, historical), HybridRanker.Limits.defaults(), null)
                .evidence().stream().map(item -> item.candidate().sourceId()).toList());
    }

    @Test
    void newMethodsKeepUnknownAndNegatedMentionsConservative() {
        HybridRanker.Candidate difference = candidate("difference", "q1",
                "平方差公式用于因式分解。", Set.of(), 0.99, 0.99);
        HybridRanker.Candidate chain = candidate("chain", "q2",
                "链式法则处理复合函数导数。", Set.of(), 0.99, 0.99);
        assertTrue(ranker.rank(new HybridRanker.Query("平方差公式与链式法则", Set.of("未知解法")),
                List.of(difference, chain), HybridRanker.Limits.defaults(), 0.1)
                .evidence().isEmpty());
        assertEquals(Set.of("平方差公式"), ChineseMathTerms.negatedMethods("不使用平方差公式"));
        assertEquals(Set.of("链式法则"), ChineseMathTerms.negatedMethods("不讨论复合函数求导"));
        assertTrue(ranker.rank(new HybridRanker.Query("二次函数不使用平方差公式", Set.of()),
                List.of(difference), HybridRanker.Limits.defaults(), 0.1).evidence().isEmpty());
        assertTrue(ranker.rank(new HybridRanker.Query("导数不讨论复合函数求导", Set.of()),
                List.of(chain), HybridRanker.Limits.defaults(), 0.1).evidence().isEmpty());
    }

    @Test
    void longTailAliasesAreLiteralAndFixedSynonymsRemainAvailableToSql() {
        Map<String, Set<String>> aliases = ChineseMathTerms.queryAliases(
                List.of("配方", "柯西施瓦茨不等式", "洛必达法则"));
        assertEquals(Set.of("配方", "配方法"), aliases.get("配方法"));
        assertEquals(Set.of("柯西施瓦茨不等式"), aliases.get("柯西施瓦茨不等式"));
        assertEquals(Set.of("洛必达法则"), aliases.get("洛必达法则"));
        assertEquals(aliases, ChineseMathTerms.aliasesForMethods(
                Set.of("配方", "柯西施瓦茨不等式", "洛必达法则")));
    }

    @Test
    void longTailMethodNeedsPositiveFullTextMentionAndIgnoresUnknownMetadata() {
        HybridRanker.Query query = new HybridRanker.Query("证明不等式",
                Set.of("柯西施瓦茨不等式"));
        HybridRanker.Candidate correct = candidate("correct", "q1",
                "柯西施瓦茨不等式用于估计平方和。", Set.of(), 0.1, 0.0);
        HybridRanker.Candidate partial = candidate("partial", "q2",
                "柯西不等式用于估计平方和。", Set.of(), 0.99, 0.99);
        HybridRanker.Candidate metadataOnly = candidate("metadata", "q3",
                "估计平方和的一般说明。", Set.of("柯西施瓦茨不等式"), 0.98, 0.98);
        HybridRanker.Candidate unrelated = candidate("unrelated", "q4",
                "证明不等式可用配方法。", Set.of("配方法"), 1.0, 1.0);
        HybridRanker.Ranking result = ranker.rank(query,
                List.of(unrelated, partial, metadataOnly, correct),
                HybridRanker.Limits.defaults(), 0.0);
        assertEquals(List.of("correct"), result.evidence().stream()
                .map(item -> item.candidate().sourceId()).toList());
        assertEquals(Set.of("柯西施瓦茨不等式"), result.evidence().get(0).matchedMethods());
    }

    @Test
    void longTailRequiresCompleteMethodBoundaryBeforeRankingHighScoringExtensions() {
        Map<String, Map<String, String>> patterns = ChineseMathTerms.literalPatternsForMethods(
                Set.of("切比雪夫不等式", "配方法"));
        assertEquals(Set.of("切比雪夫不等式"), patterns.keySet());
        assertEquals(Set.of("positive", "negative"), patterns.get("切比雪夫不等式").keySet());
        HybridRanker.Query query = new HybridRanker.Query("证明不等式", Set.of("切比雪夫不等式"));
        HybridRanker.Candidate prefixed = candidate("prefixed", "q1",
                "广义切比雪夫不等式适用。", Set.of("切比雪夫不等式"), 1.0, 1.0);
        HybridRanker.Candidate suffixed = candidate("suffixed", "q2",
                "切比雪夫不等式推广版适用。", Set.of("切比雪夫不等式"), 0.99, 0.99);
        HybridRanker.Candidate improved = candidate("improved", "q3",
                "改进切比雪夫不等式的证明。", Set.of(), 0.98, 0.98);
        HybridRanker.Candidate correct = candidate("correct", "q4",
                "切比雪夫不等式的证明。", Set.of(), 0.1, 0.1);
        assertEquals(List.of("correct"), ranker.rank(query,
                List.of(prefixed, suffixed, improved, correct),
                HybridRanker.Limits.defaults(), 0.0).evidence().stream()
                .map(item -> item.candidate().sourceId()).toList());
    }

    @Test
    void longTailNegatedPrefixAndSuffixDoNotBecomeEvidence() {
        HybridRanker.Query query = new HybridRanker.Query("求极限", Set.of("洛必达法则"));
        HybridRanker.Candidate prefix = candidate("prefix", "q1",
                "此处不使用洛必达法则，应先代入。", Set.of("洛必达法则"), 0.99, 0.99);
        HybridRanker.Candidate suffix = candidate("suffix", "q2",
                "本题洛必达法则不适用。", Set.of("洛必达法则"), 0.98, 0.98);
        HybridRanker.Candidate positive = candidate("positive", "q3",
                "洛必达法则可用于该极限。", Set.of(), 0.1, 0.1);
        assertEquals(List.of("positive"), ranker.rank(query, List.of(prefix, suffix, positive),
                HybridRanker.Limits.defaults(), null).evidence().stream()
                .map(item -> item.candidate().sourceId()).toList());
    }

    @Test
    void longTailNegativeMentionDominatesLaterPositiveMentionInSameBlock() {
        HybridRanker.Query query = new HybridRanker.Query("极限方法", Set.of("洛必达法则"));
        HybridRanker.Candidate mixed = candidate("mixed", "q1",
                "此处不使用洛必达法则；洛必达法则只在另一条件下可用。",
                Set.of("洛必达法则"), 0.99, 0.99);
        HybridRanker.Candidate positive = candidate("positive", "q2",
                "洛必达法则适用于本题的未定式。", Set.of(), 0.1, 0.1);
        assertEquals(List.of("positive"), ranker.rank(query, List.of(mixed, positive),
                HybridRanker.Limits.defaults(), null).evidence().stream()
                .map(item -> item.candidate().sourceId()).toList());
    }

    @Test
    void longTailNegationRecognizesQuotesMarkdownAndDirectPhrases() {
        HybridRanker.Query query = new HybridRanker.Query("极限方法", Set.of("洛必达法则"));
        List<String> negativeTexts = List.of(
                "不使用“洛必达法则”。",
                "**洛必达法则**不适用。",
                "不能使用洛必达法则。",
                "别用洛必达法则。",
                "别使用“洛必达法则”。",
                "这不是洛必达法则。",
                "此题并非洛必达法则。",
                "本题未使用洛必达法则。",
                "这题没有使用洛必达法则。",
                "不建议使用**洛必达法则**。",
                "这里不推荐使用洛必达法则。",
                "禁止使用‘洛必达法则’。",
                "洛必达法则不建议使用。");
        for (String text : negativeTexts) {
            HybridRanker.Candidate candidate = candidate("negative", "q1", text,
                    Set.of("洛必达法则"), 0.99, 0.99);
            assertTrue(ranker.rank(query, List.of(candidate), HybridRanker.Limits.defaults(), null)
                    .evidence().isEmpty(), text);
        }
        HybridRanker.Candidate positive = candidate("positive", "q2",
                "“洛必达法则”可用于该未定式。", Set.of(), 0.1, 0.1);
        assertEquals(List.of("positive"), ranker.rank(query, List.of(positive),
                HybridRanker.Limits.defaults(), null).evidence().stream()
                .map(item -> item.candidate().sourceId()).toList());
    }

    @Test
    void mixedFixedAndLongTailLabelsKeepBothMethodsAndDeduplicateQuestion() {
        HybridRanker.Query query = new HybridRanker.Query("方法参考",
                Set.of("配方", "柯西施瓦茨不等式"));
        HybridRanker.Candidate longTail = candidate("long-tail", "same-question",
                "柯西施瓦茨不等式的等号条件。", Set.of(), 0.9, 0.9);
        HybridRanker.Candidate sameQuestion = candidate("same-question", "same-question",
                "配方法完成平方。", Set.of("配方法"), 0.8, 0.8);
        HybridRanker.Candidate another = candidate("another", "other-question",
                "配方法处理二次项。", Set.of(), 0.7, 0.7);
        HybridRanker.Ranking result = ranker.rank(query,
                List.of(longTail, sameQuestion, another), HybridRanker.Limits.defaults(), null);
        assertEquals(List.of("long-tail", "another"), result.evidence().stream()
                .map(item -> item.candidate().sourceId()).toList());
    }

    @Test
    void longTailInputBoundsAndNoiseAreConservative() {
        String label80 = "柯".repeat(80);
        assertEquals(Set.of(label80), ChineseMathTerms.queryAliases(List.of(label80)).keySet());
        assertEquals(8, ChineseMathTerms.queryAliases(List.of("甲乙丙", "甲乙丁", "甲乙戊", "甲乙己",
                "甲乙庚", "甲乙辛", "甲乙壬", "甲乙癸")).size());
        assertThrows(IllegalArgumentException.class, () -> ChineseMathTerms.queryAliases(
                List.of("柯西施瓦茨不等式", "柯西施瓦茨不等式", "柯西施瓦茨不等式",
                "柯西施瓦茨不等式", "柯西施瓦茨不等式", "柯西施瓦茨不等式",
                "柯西施瓦茨不等式", "柯西施瓦茨不等式", "柯西施瓦茨不等式")));
        assertThrows(IllegalArgumentException.class, () -> ChineseMathTerms.queryAliases(
                List.of("柯".repeat(81))));
        assertThrows(IllegalArgumentException.class, () -> ChineseMathTerms.queryAliases(
                List.of("㍿".repeat(27))));
        assertThrows(IllegalArgumentException.class, () -> ChineseMathTerms.queryAliases(
                List.of("柯西\n不等式")));
        for (String rejected : List.of(" ", "!!!", "12345", "柯西", "未知方法", "其他方法",
                "解题方法", "不使用洛必达法则", "别用洛必达法则",
                "别使用洛必达法则", "非洛必达法则", "不是洛必达法则",
                "并非洛必达法则", "未使用洛必达法则", "没有使用洛必达法则",
                "不推荐使用洛必达法则",
                "洛必达法则不适用")) {
            assertTrue(ChineseMathTerms.queryAliases(List.of(rejected)).isEmpty(), rejected);
        }
    }

    @Test
    void negatedLongTailLabelAndMatchingBodyCannotBecomeEvidence() {
        for (String negated : List.of("别用洛必达法则", "非洛必达法则")) {
            HybridRanker.Query query = new HybridRanker.Query(negated, Set.of(negated));
            HybridRanker.Candidate sameText = candidate("same", "q1", negated,
                    Set.of(negated), 1.0, 1.0);
            assertTrue(ChineseMathTerms.queryAliases(List.of(negated)).isEmpty());
            assertTrue(ranker.rank(query, List.of(sameText), HybridRanker.Limits.defaults(), 0.0)
                    .evidence().isEmpty(), negated);
        }
        HybridRanker.Query standard = new HybridRanker.Query("求极限", Set.of("洛必达法则"));
        HybridRanker.Candidate denied = candidate("denied", "q2", "别用洛必达法则。",
                Set.of("洛必达法则"), 1.0, 1.0);
        assertTrue(ranker.rank(standard, List.of(denied), HybridRanker.Limits.defaults(), 0.0)
                .evidence().isEmpty());
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
