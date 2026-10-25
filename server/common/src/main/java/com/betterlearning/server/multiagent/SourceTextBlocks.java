package com.betterlearning.server.multiagent;

import java.util.ArrayList;
import java.util.List;

/** 来源正文块的唯一确定性划分，供索引与resolve共用；保留原文字符，不做渲染重写。 */
public final class SourceTextBlocks {
    private SourceTextBlocks() { }

    /** 空行分段；显示公式需有闭合符，代码围栏沿用至文件末尾，仅规范化CRLF。 */
    public static List<String> split(String markdown) {
        if (markdown == null) {
            throw new IllegalArgumentException("source body required");
        }
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        int[] lastBracketClose = new int[lines.length];
        int[] nextBracketClose = new int[lines.length];
        int[] nextDollarClose = new int[lines.length];
        boolean[] dollars = new boolean[lines.length];
        for (int index = 0; index < lines.length; index++) {
            lastBracketClose[index] = lastClose(lines[index]);
            dollars[index] = lines[index].strip().equals("$$");
        }
        int bracket = -1;
        int dollar = -1;
        for (int index = lines.length - 1; index >= 0; index--) {
            nextBracketClose[index] = bracket;
            nextDollarClose[index] = dollar;
            if (lastBracketClose[index] >= 0) {
                bracket = index;
            }
            if (dollars[index]) {
                dollar = index;
            }
        }
        List<String> blocks = new ArrayList<>();
        List<String> block = new ArrayList<>();
        char fence = 0;
        int fenceLength = 0;
        MathKind math = MathKind.NONE;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            boolean protectedLine = fence != 0 || math != MathKind.NONE;
            if (fence != 0) {
                if (closesFence(line, fence, fenceLength)) {
                    fence = 0;
                }
            } else if (math == MathKind.DOLLAR) {
                if (dollars[index]) {
                    math = MathKind.NONE;
                }
            } else {
                int openingFence = math == MathKind.NONE ? fenceLength(line) : 0;
                if (openingFence >= 3) {
                    fence = line.stripLeading().charAt(0);
                    fenceLength = openingFence;
                    protectedLine = true;
                } else if (math == MathKind.NONE && dollars[index] && nextDollarClose[index] >= 0) {
                    math = MathKind.DOLLAR;
                    protectedLine = true;
                } else {
                    MathKind updated = bracketState(line, index, math,
                            lastBracketClose[index], nextBracketClose[index]);
                    protectedLine |= updated == MathKind.BRACKET;
                    math = updated;
                }
            }
            if (line.isBlank() && !protectedLine) {
                append(blocks, block);
            } else {
                block.add(line);
            }
        }
        append(blocks, block);
        return List.copyOf(blocks);
    }

    private static MathKind bracketState(String line, int lineIndex, MathKind state,
            int lastClose, int nextClose) {
        for (int index = 0; index + 1 < line.length(); index++) {
            char next = line.charAt(index + 1);
            if (line.charAt(index) != '\\' || (next != '[' && next != ']')
                    || escapedSlash(line, index)) {
                continue;
            }
            if (state == MathKind.BRACKET && next == ']') {
                state = MathKind.NONE;
            } else if (state == MathKind.NONE && next == '['
                    && (lastClose > index || nextClose > lineIndex)) {
                state = MathKind.BRACKET;
            }
            index++;
        }
        return state;
    }

    private static int lastClose(String line) {
        int last = -1;
        for (int index = 0; index + 1 < line.length(); index++) {
            if (line.charAt(index) == '\\' && line.charAt(index + 1) == ']'
                    && !escapedSlash(line, index)) {
                last = index;
                index++;
            }
        }
        return last;
    }

    private static boolean escapedSlash(String line, int index) {
        int preceding = 0;
        for (int cursor = index - 1; cursor >= 0 && line.charAt(cursor) == '\\'; cursor--) {
            preceding++;
        }
        return preceding % 2 != 0;
    }

    private static int fenceLength(String line) {
        String stripped = line.stripLeading();
        if (stripped.isEmpty() || (stripped.charAt(0) != '`' && stripped.charAt(0) != '~')) {
            return 0;
        }
        char marker = stripped.charAt(0);
        int repeated = 0;
        while (repeated < stripped.length() && stripped.charAt(repeated) == marker) {
            repeated++;
        }
        return repeated;
    }

    private static boolean closesFence(String line, char marker, int length) {
        String stripped = line.stripLeading();
        int repeated = fenceLength(line);
        return !stripped.isEmpty() && stripped.charAt(0) == marker
                && repeated >= length && stripped.substring(repeated).isBlank();
    }

    private enum MathKind {
        NONE, BRACKET, DOLLAR
    }

    private static void append(List<String> blocks, List<String> block) {
        if (!block.isEmpty()) {
            blocks.add(String.join("\n", block));
            block.clear();
        }
    }
}
