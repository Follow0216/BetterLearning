package com.betterlearning.server.agent.retrieval.ranking;

import java.text.Normalizer;
import java.util.Collection;
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
            + "不用|别用|别使用|不能用|不能使用|不建议使用|不推荐使用|禁止使用|不要用|不要使用|无需|无须|避免|排除|不适用|不是|并非|未使用|没有使用|非)"
            + "(?:该|此|这|种|方法|的|用){0,4}$");
    private static final Pattern NEGATED_LABEL = Pattern.compile("^(?:不讨论|不使用|不采用|不考虑|"
            + "不用|别用|别使用|不能用|不能使用|不建议使用|不推荐使用|禁止使用|不要|无需|无须|避免|排除|不适用|不是|并非|未使用|没有使用).*");
    private static final Set<String> NEGATED_SUFFIXES = Set.of("不适用", "不可用", "不能用",
            "不能使用", "不需要", "不宜", "不建议使用", "不推荐使用", "禁止使用");
    // These plain ARE constructs work in both java.util.regex and PostgreSQL's ~ operator.
    // The finite context list deliberately rejects unrecognized adjacent Han characters.
    private static final String LITERAL_BOUNDARY = "[，。；：！？、（）【】《》“”‘’\"'*`_.,;:!?(){}<>/=+|]";
    private static final String LITERAL_BEFORE = "(^|" + LITERAL_BOUNDARY
            + "|使用|应用|利用|采用|根据|依据|通过)";
    private static final String LITERAL_AFTER = "($|" + LITERAL_BOUNDARY
            + "|适用|用于|可用于|可以|能够|给出|成立|的等号|的条件|的结论|的证明|的应用|的步骤|的完整步骤)";
    private static final String NEGATION_BEFORE = "(不讨论|不使用|不采用|不考虑|不用|别用|别使用|"
            + "不能用|不能使用|不建议使用|不推荐使用|禁止使用|不要用|不要使用|"
            + "无需|无须|避免|排除|不适用|不是|并非|未使用|没有使用|非)"
            + "(该|此|这|种|方法|的|用){0,4}[“”‘’\"'*`_]*";
    private static final String NEGATION_AFTER = "[“”‘’\"'*`_]*"
            + "(不适用|不可用|不能用|不能使用|不需要|不宜|不建议使用|不推荐使用|禁止使用)";
    private static final Set<String> GENERIC_LABELS = Set.of("未知方法", "其他方法", "其它方法",
            "解题方法", "通用方法", "一般方法", "常见方法", "数学方法", "任意方法",
            "未知解法", "其他解法", "其它解法", "解题思路", "其他", "其它", "未知");
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

    /**
     * SQL shortlisting aliases. Fixed methods keep synonyms; safe long-tail labels use one literal.
     * At most eight raw labels of at most 80 UTF-16 units each are accepted. Long-tail literals
     * require three units, a Han character, and only letters/digits after NFKC normalization;
     * generic and negated labels (including a leading 非) are omitted. Metadata is never proof
     * of a long-tail method. This conservative rule intentionally excludes some valid names.
     */
    public static Map<String, Set<String>> queryAliases(Collection<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return Map.of();
        }
        if (labels.size() > 8) {
            throw new IllegalArgumentException("too many method labels");
        }
        Map<String, Set<String>> selected = new LinkedHashMap<>();
        for (String raw : labels) {
            if (raw == null || raw.length() > 80 || raw.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("invalid method label structure");
            }
            String normalized = normalize(raw);
            if (normalized.length() > 80) {
                throw new IllegalArgumentException("normalized method label too long");
            }
            String canonical = METHOD_ALIASES.get(normalized);
            if (canonical != null) {
                Set<String> synonyms = new LinkedHashSet<>();
                for (Map.Entry<String, String> alias : METHOD_ALIASES.entrySet()) {
                    if (canonical.equals(alias.getValue())) {
                        synonyms.add(alias.getKey());
                    }
                }
                selected.put(canonical, Set.copyOf(synonyms));
            } else if (validLiteral(normalized)) {
                selected.put(normalized, Set.of(normalized));
            }
        }
        return Map.copyOf(selected);
    }

    /** Kept as the SQL-facing compatibility entrypoint. */
    public static Map<String, Set<String>> aliasesForMethods(Set<String> labels) {
        return queryAliases(labels);
    }

    /**
     * Shared Java/PostgreSQL ARE patterns for safe long-tail method literals only.
     * The caller binds each pattern as a SQL parameter. Source text must use the same NFKC,
     * lower-case and whitespace normalization as Java before applying the SQL patterns.
     * Positive matches require an explicit boundary or a listed grammar cue on both sides;
     * any listed negative mention of the literal vetoes the whole source block.
     */
    public static Map<String, Map<String, String>> literalPatternsForMethods(Set<String> methods) {
        Map<String, Map<String, String>> patterns = new LinkedHashMap<>();
        if (methods == null) {
            return Map.of();
        }
        for (String method : methods) {
            if (method != null && !METHOD_ALIASES.containsValue(method) && validLiteral(method)) {
                patterns.put(method, Map.of(
                        "positive", LITERAL_BEFORE + method + LITERAL_AFTER,
                        "negative", "(" + NEGATION_BEFORE + method + "|" + method + NEGATION_AFTER + ")"));
            }
        }
        return Map.copyOf(patterns);
    }

    /** Long-tail method evidence must occur positively in source text, never only in metadata. */
    static Set<String> literalMethodsInText(String text, Map<String, Set<String>> queryAliases) {
        String normalizedText = normalize(text);
        Set<String> matched = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> entry
                : literalPatternsForMethods(queryAliases.keySet()).entrySet()) {
            Map<String, String> patterns = entry.getValue();
            if (Pattern.compile(patterns.get("positive")).matcher(normalizedText).find()
                    && !Pattern.compile(patterns.get("negative")).matcher(normalizedText).find()) {
                matched.add(entry.getKey());
            }
        }
        return Set.copyOf(matched);
    }

    private static boolean validLiteral(String label) {
        if (label.length() < 3 || GENERIC_LABELS.contains(label)
                || label.startsWith("未知") || label.startsWith("其他") || label.startsWith("其它")
                || label.startsWith("非")
                || NEGATED_LABEL.matcher(label).matches()
                || NEGATED_SUFFIXES.stream().anyMatch(label::endsWith)) {
            return false;
        }
        boolean han = false;
        for (int offset = 0; offset < label.length();) {
            int codePoint = label.codePointAt(offset);
            if (!Character.isLetterOrDigit(codePoint)) {
                return false;
            }
            han |= Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
            offset += Character.charCount(codePoint);
        }
        return han;
    }

    /** Negated mentions are excluded from positive query methods. */
    public static Set<String> methodsInText(String text) {
        return mentions(text).positive();
    }

    public static Set<String> negatedMethods(String text) {
        return mentions(text).negative();
    }

    private static Mentions mentions(String text) {
        return mentions(text, METHOD_ALIASES, false);
    }

    private static Mentions mentions(String text, Map<String, String> aliasesByText,
            boolean negativeDominates) {
        Set<String> positive = new LinkedHashSet<>();
        Set<String> negative = new LinkedHashSet<>();
        String normalizedText = normalize(text);
        boolean[] consumed = new boolean[normalizedText.length()];
        var aliases = aliasesByText.entrySet().stream()
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
        if (negativeDominates) {
            positive.removeAll(negative);
        } else {
            negative.removeAll(positive);
        }
        return new Mentions(Set.copyOf(positive), Set.copyOf(negative));
    }

    private static boolean negated(String text, int start, int end) {
        String prefix = trimDecorations(text.substring(Math.max(0, start - 16), start), false);
        String suffix = trimDecorations(text.substring(end, Math.min(text.length(), end + 16)), true);
        return NEGATED_PREFIX.matcher(prefix).matches() || suffix.startsWith("不适用")
                || suffix.startsWith("不可用") || suffix.startsWith("不能用")
                || suffix.startsWith("不能使用") || suffix.startsWith("不需要")
                || suffix.startsWith("不宜") || suffix.startsWith("不建议使用")
                || suffix.startsWith("不推荐使用")
                || suffix.startsWith("禁止使用");
    }

    private static String trimDecorations(String value, boolean leading) {
        int start = 0;
        int end = value.length();
        if (leading) {
            while (start < end && decoration(value.charAt(start))) {
                start++;
            }
        } else {
            while (end > start && decoration(value.charAt(end - 1))) {
                end--;
            }
        }
        return value.substring(start, end);
    }

    private static boolean decoration(char value) {
        return value == '“' || value == '”' || value == '‘' || value == '’'
                || value == '"' || value == '\'' || value == '*' || value == '_'
                || value == '`';
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
