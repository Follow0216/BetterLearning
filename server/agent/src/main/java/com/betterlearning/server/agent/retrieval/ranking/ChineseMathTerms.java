package com.betterlearning.server.agent.retrieval.ranking;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** A deliberately small, controlled Chinese math vocabulary, pending corpus evaluation. */
public final class ChineseMathTerms {
    private static final Map<String, String> METHOD_ALIASES = aliases();
    private static final Pattern NEGATED_PREFIX = Pattern.compile(".*(?:不讨论|不使用|不采用|不考虑|"
            + "不用|不能用|不要用|不要使用|无需|无须|避免|排除|不适用|非)"
            + "(?:该|此|这|种|方法|的|用){0,4}$");
    private static final Set<String> TERMS = Set.of(
            "二次函数", "一元一次方程", "一元二次方程", "方程组", "不等式", "函数单调性", "导数",
            "极值", "最值", "判别式", "韦达定理", "完全平方", "勾股定理",
            "相似三角形", "全等三角形", "辅助线", "数列", "等差数列",
            "等比数列", "概率", "排列组合", "反证法", "递推", "面积",
            "因式分解", "平方差公式", "链式法则", "分式", "绝对值",
            "三角函数", "解析几何", "圆锥曲线");

    private ChineseMathTerms() {
    }

    public static Set<String> methods(String text, Set<String> labels) {
        Set<String> found = new LinkedHashSet<>(methodsInText(text));
        found.addAll(explicitMethods(labels));
        return Set.copyOf(found);
    }

    /** Only full controlled labels count as explicit method candidates. */
    public static Set<String> explicitMethods(Set<String> labels) {
        Set<String> found = new LinkedHashSet<>();
        if (labels != null) {
            for (String label : labels) {
                String canonical = METHOD_ALIASES.get(normalize(label));
                if (canonical != null) {
                    found.add(canonical);
                }
            }
        }
        return Set.copyOf(found);
    }

    /** SQL uses these controlled aliases only to shortlist; Java still validates full method semantics. */
    public static Map<String, Set<String>> aliasesForMethods(Set<String> labels) {
        Set<String> canonical = explicitMethods(labels);
        Map<String, Set<String>> selected = new LinkedHashMap<>();
        for (Map.Entry<String, String> alias : METHOD_ALIASES.entrySet()) {
            if (canonical.contains(alias.getValue())) {
                selected.computeIfAbsent(alias.getValue(), ignored -> new LinkedHashSet<>()).add(alias.getKey());
            }
        }
        selected.replaceAll((method, values) -> Set.copyOf(values));
        return Map.copyOf(selected);
    }

    /** Negated mentions are excluded from positive query methods. */
    public static Set<String> methodsInText(String text) {
        return mentions(text).positive();
    }

    public static Set<String> negatedMethods(String text) {
        return mentions(text).negative();
    }

    private static Mentions mentions(String text) {
        Set<String> positive = new LinkedHashSet<>();
        Set<String> negative = new LinkedHashSet<>();
        String normalizedText = normalize(text);
        boolean[] consumed = new boolean[normalizedText.length()];
        var aliases = METHOD_ALIASES.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> alias) -> alias.getKey().length()).reversed()).toList();
        for (Map.Entry<String, String> alias : aliases) {
            int at = normalizedText.indexOf(alias.getKey());
            while (at >= 0) {
                int end = at + alias.getKey().length();
                boolean overlap = false;
                for (int position = at; position < end; position++) {
                    overlap |= consumed[position];
                }
                if (!overlap) {
                    if (negated(normalizedText, at, end)) {
                        negative.add(alias.getValue());
                    } else {
                        positive.add(alias.getValue());
                    }
                    for (int position = at; position < end; position++) {
                        consumed[position] = true;
                    }
                }
                at = normalizedText.indexOf(alias.getKey(), at + 1);
            }
        }
        negative.removeAll(positive);
        return new Mentions(Set.copyOf(positive), Set.copyOf(negative));
    }

    private static boolean negated(String text, int start, int end) {
        String prefix = text.substring(Math.max(0, start - 10), start);
        String suffix = text.substring(end, Math.min(text.length(), end + 4));
        return NEGATED_PREFIX.matcher(prefix).matches() || suffix.startsWith("不适用")
                || suffix.startsWith("不可用") || suffix.startsWith("不能用")
                || suffix.startsWith("不需要") || suffix.startsWith("不宜");
    }

    public static Set<String> terms(String text) {
        String normalized = normalize(text);
        Set<String> found = new LinkedHashSet<>();
        for (String term : TERMS) {
            if (normalized.contains(term)) {
                found.add(term);
            }
        }
        return Set.copyOf(found);
    }

    private static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("配方法", "配方法");
        aliases.put("配方", "配方法");
        // P3 SOLVE currently emits this exact label in its supported pipeline fixture.
        aliases.put("一元一次方程", "一元一次方程");
        aliases.put("平方差公式", "平方差公式");
        aliases.put("平方差", "平方差公式");
        aliases.put("链式法则", "链式法则");
        aliases.put("复合函数求导", "链式法则");
        aliases.put("链式求导", "链式法则");
        aliases.put("换元法", "换元法");
        aliases.put("换元", "换元法");
        aliases.put("分类讨论", "分类讨论");
        aliases.put("分情况讨论", "分类讨论");
        aliases.put("待定系数法", "待定系数法");
        aliases.put("消元法", "消元法");
        aliases.put("代入消元", "消元法");
        aliases.put("加减消元", "消元法");
        aliases.put("相似三角形", "相似三角形");
        aliases.put("数学归纳法", "数学归纳法");
        aliases.put("归纳法", "数学归纳法");
        aliases.put("反证法", "反证法");
        aliases.put("数形结合", "数形结合");
        aliases.put("因式分解", "因式分解");
        return Map.copyOf(aliases);
    }

    private record Mentions(Set<String> positive, Set<String> negative) {
    }
}
