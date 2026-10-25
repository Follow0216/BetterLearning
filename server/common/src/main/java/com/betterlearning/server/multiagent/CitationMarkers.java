package com.betterlearning.server.multiagent;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 草稿标记到发布标记的确定性转换，绝不让模型决定citationId或资源URL。 */
public final class CitationMarkers {
    private static final Pattern EVIDENCE = Pattern.compile("\\[\\[evidence:([0-9a-f-]{36})]]");
    private CitationMarkers() { }

    public static String resolve(String markdown, Map<String, String> citations) {
        if (markdown == null || markdown.contains("[[citation:")) {
            throw new IllegalArgumentException("model cannot mint citations");
        }
        var matcher = EVIDENCE.matcher(markdown);
        Set<String> used = new LinkedHashSet<>();
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String evidence = matcher.group(1);
            String citation = citations.get(evidence);
            if (citation == null) {
                throw new IllegalArgumentException("unknown evidence marker");
            }
            used.add(evidence);
            matcher.appendReplacement(rendered, java.util.regex.Matcher.quoteReplacement("[[citation:" + citation + "]]"));
        }
        matcher.appendTail(rendered);
        if (!used.equals(citations.keySet()) || rendered.toString().contains("[[evidence:")) {
            throw new IllegalArgumentException("evidence marker set mismatch");
        }
        return rendered.toString();
    }
}
