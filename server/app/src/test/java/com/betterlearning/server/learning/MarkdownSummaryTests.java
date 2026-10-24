package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.betterlearning.server.learning.validation.MarkdownSummary;

import org.junit.jupiter.api.Test;

class MarkdownSummaryTests {

    @Test
    void stripsCommonMarkupAndCollapsesWhitespace() {
        assertThat(MarkdownSummary.from("# 求导\n求函数 **f(x)=x²** 的导数。"))
                .isEqualTo("求导 求函数 f(x)=x² 的导数。");
        assertThat(MarkdownSummary.from("[链接](https://example.test)  `代码`\n> 引用"))
                .isEqualTo("链接 代码 引用");
        assertThat(MarkdownSummary.from("a < b > c")).isEqualTo("a < b > c");
    }

    @Test
    void limitsCodePointsWithoutSplittingEmojiAndProvidesImageFallback() {
        String input = "😀".repeat(51);
        assertThat(MarkdownSummary.from(input)).isEqualTo("😀".repeat(50));
        assertThat(MarkdownSummary.from("![图片](https://example.test/image.png)"))
                .isEqualTo("（无文本预览）");
    }
}
