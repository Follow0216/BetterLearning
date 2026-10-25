package com.betterlearning.server.multiagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceTextBlocksTests {
    @Test
    void bracketDisplayFormulaKeepsItsOwnBlankLinesAndSplitsOutside() {
        assertThat(SourceTextBlocks.split("甲\n\n\\[\na\n\nb\n\\]\n\n乙"))
                .containsExactly("甲", "\\[\na\n\nb\n\\]", "乙");
    }

    @Test
    void indentedCrLfAndEmojiRemainExactExceptLineEndings() {
        assertThat(SourceTextBlocks.split("  😀 前 \\[x\r\n  \r\n y\\] 尾\r\n\r\n末😀"))
                .containsExactly("  😀 前 \\[x\n  \n y\\] 尾", "末😀");
    }

    @Test
    void dollarFormulaTreatsFenceLookingLineAndBracketTokensAsText() {
        assertThat(SourceTextBlocks.split("$$\nx\n\n```\n\\[\n\ny\n$$\n\nafter"))
                .containsExactly("$$\nx\n\n```\n\\[\n\ny\n$$", "after");
    }

    @Test
    void bracketFormulaTreatsFenceLookingLineAndDollarTokensAsText() {
        assertThat(SourceTextBlocks.split("\\[\nx\n\n```\n$$\n\ny\n\\]\n\nafter"))
                .containsExactly("\\[\nx\n\n```\n$$\n\ny\n\\]", "after");
    }

    @Test
    void codeFenceTreatsBothFormulaDelimitersAsLiteralText() {
        assertThat(SourceTextBlocks.split("~~~tex\n\\[\n\n\\]\n$$\n\n~~~\n\n后"))
                .containsExactly("~~~tex\n\\[\n\n\\]\n$$\n\n~~~", "后");
    }

    @Test
    void standaloneAndInlineBracketDelimitersBothProtectMatchedSpan() {
        assertThat(SourceTextBlocks.split("\\[\na\n\nb\n\\]\n\n先 \\[c\n\nd\\] 后"))
                .containsExactly("\\[\na\n\nb\n\\]", "先 \\[c\n\nd\\] 后");
    }

    @Test
    void unmatchedMathFallsBackToOrdinaryBlankLineSplittingWithoutDroppingTail() {
        assertThat(SourceTextBlocks.split("$$\na\n\nb终"))
                .containsExactly("$$\na", "b终");
        assertThat(SourceTextBlocks.split("\\[\na\n\nb终"))
                .containsExactly("\\[\na", "b终");
        assertThat(SourceTextBlocks.split("\\[\na\n\n```\n\ncode\n```\n\nlast"))
                .containsExactly("\\[\na", "```\n\ncode\n```", "last");
    }

    @Test
    void existingDollarAndCodeFenceFormsStillKeepInternalBlankLines() {
        assertThat(SourceTextBlocks.split("  $$  \nx\n\n y\n$$\n\n尾"))
                .containsExactly("  $$  \nx\n\n y\n$$", "尾");
        assertThat(SourceTextBlocks.split("````js\n\nx\n````\n\n尾"))
                .containsExactly("````js\n\nx\n````", "尾");
        assertThat(SourceTextBlocks.split("```\n\n末尾"))
                .containsExactly("```\n\n末尾");
    }

    @Test
    void escapedOpeningAndUnpairedClosingStayLiteral() {
        assertThat(SourceTextBlocks.split("\\\\[\na\n\nb\\]\n末"))
                .containsExactly("\\\\[\na", "b\\]\n末");
        assertThat(SourceTextBlocks.split("\\] 保留\n\n最后😀"))
                .containsExactly("\\] 保留", "最后😀");
    }
}
