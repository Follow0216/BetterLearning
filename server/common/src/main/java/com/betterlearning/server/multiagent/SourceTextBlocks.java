package com.betterlearning.server.multiagent;

import java.util.ArrayList;
import java.util.List;

/** 来源正文块的唯一确定性划分，供索引与resolve共用；保留原文字符，不做渲染重写。 */
public final class SourceTextBlocks {
    private SourceTextBlocks() { }

    /** 空行分段；代码围栏和独立 $$ 公式内的空行属于原块；仅规范化CRLF。 */
    public static List<String> split(String markdown) {
        if (markdown == null) {
            throw new IllegalArgumentException("source body required");
        }
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        List<String> blocks = new ArrayList<>();
        List<String> block = new ArrayList<>();
        char fence = 0;
        int fenceLength = 0;
        boolean math = false;
        for (String line : lines) {
            String stripped = line.stripLeading();
            char first = stripped.isEmpty() ? 0 : stripped.charAt(0);
            int repeated = 0;
            if (first == '`' || first == '~') {
                while (repeated < stripped.length() && stripped.charAt(repeated) == first) {
                    repeated++;
                }
            }
            boolean blank = line.isBlank();
            if (blank && fence == 0 && !math) {
                append(blocks, block);
                continue;
            }
            block.add(line);
            if (fence != 0) {
                if (first == fence && repeated >= fenceLength && stripped.substring(repeated).isBlank()) {
                    fence = 0;
                }
            } else if (!math && repeated >= 3) {
                fence = first;
                fenceLength = repeated;
            } else if (stripped.stripTrailing().equals("$$")) {
                math = !math;
            }
        }
        append(blocks, block);
        return List.copyOf(blocks);
    }

    private static void append(List<String> blocks, List<String> block) {
        if (!block.isEmpty()) {
            blocks.add(String.join("\n", block));
            block.clear();
        }
    }
}
