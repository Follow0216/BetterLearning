package com.betterlearning.server.agent.retrieval.anchor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.betterlearning.server.multiagent.SourceTextBlocks;

/** common 负责唯一的正文分段；此层只补引用所需位置、摘要和输入边界。 */
public final class MarkdownAnchorParser {
    private static final int MAX_BYTES = 1024 * 1024;

    private MarkdownAnchorParser() {
    }

    public enum Kind {
        PARAGRAPH, FORMULA
    }

    /** start/end 相对规范化全文；startOffset/endOffset 相对返回的原文块，均为 UTF-16。 */
    public record Block(int ordinal, Kind kind, String text, int start, int end,
            int startOffset, int endOffset, String contentSha256) {
    }

    public static List<Block> parse(String markdown) {
        if (markdown == null) {
            throw new IllegalArgumentException("markdown is required");
        }
        String source = markdown.replace("\r\n", "\n");
        validateUnicode(source);
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("markdown is too large");
        }
        List<Block> blocks = new ArrayList<>();
        int cursor = 0;
        for (String original : SourceTextBlocks.split(source)) {
            int start = source.indexOf(original, cursor);
            if (start < 0) {
                throw new IllegalStateException("source block does not match original body");
            }
            int end = start + original.length();
            blocks.add(new Block(blocks.size(), formulaBlock(original) ? Kind.FORMULA : Kind.PARAGRAPH,
                    original, start, end, 0, original.length(), sha256(original)));
            cursor = end;
        }
        return List.copyOf(blocks);
    }

    private static boolean formulaBlock(String text) {
        String value = text.strip();
        return value.startsWith("$$") && value.endsWith("$$") && value.length() > 4
                || value.startsWith("\\[") && value.endsWith("\\]") && value.length() > 4;
    }

    private static void validateUnicode(String source) {
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (i + 1 >= source.length() || !Character.isLowSurrogate(source.charAt(++i))) {
                    throw new IllegalArgumentException("invalid UTF-16 surrogate");
                }
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("invalid UTF-16 surrogate");
            }
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
