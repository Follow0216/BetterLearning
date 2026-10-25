package com.betterlearning.server.agent.retrieval.ranking;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned actual-method names for handwriting OCR candidates.
 * This does not replace the legacy query vocabulary in ChineseMathTerms.
 * A new version requires a planned reindex and compatibility decision.
 */
public final class HandwritingMethodTaxonomy {
    public static final String VERSION = "math-methods-v1";
    private static final Map<String, String> ALIASES = aliases();
    private static final Set<String> METHODS = Set.copyOf(ALIASES.values());

    private HandwritingMethodTaxonomy() {
    }

    public static Optional<String> canonicalMethod(String label) {
        if (label == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ALIASES.get(normalize(label)));
    }

    public static Set<String> methods() {
        return METHODS;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static Map<String, String> aliases() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("配方法", "配方法");
        values.put("配方", "配方法");
        values.put("平方差公式", "平方差公式");
        values.put("平方差", "平方差公式");
        values.put("链式法则", "链式法则");
        values.put("复合函数求导", "链式法则");
        values.put("链式求导", "链式法则");
        values.put("换元法", "换元法");
        values.put("换元", "换元法");
        values.put("分类讨论", "分类讨论");
        values.put("分情况讨论", "分类讨论");
        values.put("待定系数法", "待定系数法");
        values.put("消元法", "消元法");
        values.put("代入消元", "消元法");
        values.put("加减消元", "消元法");
        values.put("相似三角形", "相似三角形");
        values.put("数学归纳法", "数学归纳法");
        values.put("归纳法", "数学归纳法");
        values.put("反证法", "反证法");
        values.put("数形结合", "数形结合");
        values.put("因式分解", "因式分解");
        return Map.copyOf(values);
    }
}
