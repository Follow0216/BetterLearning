package com.betterlearning.server.learning.validation;

import java.util.regex.Pattern;

/** 小型 Markdown 文本预览器；不执行 HTML，也不访问图片或链接。 */
public final class MarkdownSummary {

    private static final int PREVIEW_CODE_POINTS = 50;

    private static final Pattern IMAGE = Pattern.compile("!\\[[^]\\r\\n]*]\\([^\\r\\n)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^]\\r\\n]+)]\\([^\\r\\n)]*\\)");
    private static final Pattern HTML = Pattern.compile("</?[A-Za-z][^>]*>");
    private static final Pattern LINE_MARKER = Pattern.compile(
            "(?m)^\\s{0,3}(?:#{1,6}\\s+|>\\s*|[-+*]\\s+|[0-9]+[.)]\\s+)");
    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Z}]+", Pattern.UNICODE_CHARACTER_CLASS);

    private MarkdownSummary() {
    }

    /** 从原文派生最多 50 个 Unicode 码点，保留普通数学表达式。 */
    public static String from(String markdown) {
        String text = IMAGE.matcher(markdown).replaceAll("");
        text = LINK.matcher(text).replaceAll("$1");
        text = HTML.matcher(text).replaceAll("");
        text = LINE_MARKER.matcher(text).replaceAll("");
        text = text.replace("```", "").replace("~~~", "")
                .replace("`", "").replace("**", "").replace("__", "")
                .replace("*", "").replace("_", "").replace("~~", "");
        text = SPACE.matcher(text).replaceAll(" ").strip();
        if (text.isEmpty()) {
            return "（无文本预览）";
        }
        int count = text.codePointCount(0, text.length());
        return count <= PREVIEW_CODE_POINTS ? text
                : text.substring(0, text.offsetByCodePoints(0, PREVIEW_CODE_POINTS));
    }
}
