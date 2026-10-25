package com.betterlearning.server.agent.retrieval.anchor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.betterlearning.server.multiagent.SourceTextBlocks;

import org.junit.jupiter.api.Test;

class MarkdownAnchorParserTests {
    @Test
    void preservesOriginalFormulaAndUnicodeOffsetsAfterCrLfNormalization() throws Exception {
        String markdown = "题😀\r\n续行\r\n\r\n$$\r\n\\frac{1}{2}\r\n\r\n+1\r\n$$\r\n\r\n末段";
        List<MarkdownAnchorParser.Block> blocks = MarkdownAnchorParser.parse(markdown);
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).text()).isEqualTo("题😀\n续行");
        assertThat(blocks.get(0).endOffset()).isEqualTo(6);
        assertThat(blocks.get(0).contentSha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(blocks.get(0).text().getBytes(StandardCharsets.UTF_8))));
        assertThat(blocks.get(1).kind()).isEqualTo(MarkdownAnchorParser.Kind.FORMULA);
        assertThat(blocks.get(1).text()).isEqualTo("$$\n\\frac{1}{2}\n\n+1\n$$");
        assertThat(blocks.get(1).contentSha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(blocks.get(1).text().getBytes(StandardCharsets.UTF_8))));
        assertThat(MarkdownAnchorParser.parse(markdown)).isEqualTo(blocks);
        assertThat(blocks).extracting(MarkdownAnchorParser.Block::text)
                .containsExactlyElementsOf(SourceTextBlocks.split(markdown));
        assertThat(blocks.get(1).start()).isEqualTo(markdown.replace("\r\n", "\n").indexOf("$$"));
    }

    @Test
    void rejectsMalformedUnicodeAndOversizedText() {
        assertThatThrownBy(() -> MarkdownAnchorParser.parse("x\ud83d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarkdownAnchorParser.parse("a".repeat(1024 * 1024 + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void followsCommonBlankLineBoundariesWithoutSplittingAdjacentFormulaLines() {
        List<MarkdownAnchorParser.Block> blocks = MarkdownAnchorParser.parse(
                "正文 $x$\n$$x^2$$\n\\[a+b\\]\n结尾");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).kind()).isEqualTo(MarkdownAnchorParser.Kind.PARAGRAPH);
        assertThat(blocks.get(0).text()).isEqualTo("正文 $x$\n$$x^2$$\n\\[a+b\\]\n结尾");
        List<MarkdownAnchorParser.Block> separated = MarkdownAnchorParser.parse("正文\n\n$$x^2$$\n\n结尾");
        assertThat(separated).extracting(MarkdownAnchorParser.Block::kind).containsExactly(
                MarkdownAnchorParser.Kind.PARAGRAPH, MarkdownAnchorParser.Kind.FORMULA,
                MarkdownAnchorParser.Kind.PARAGRAPH);
    }
}
