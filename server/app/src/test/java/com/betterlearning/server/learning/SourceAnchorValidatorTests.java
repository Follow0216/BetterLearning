package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.learning.validation.SourceAnchorValidator;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.AnchorLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.HandwritingLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.HandwritingSegment;
import com.betterlearning.server.multiagent.MultiAgentModels.MarkdownLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.Source;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceVersion;
import com.betterlearning.server.multiagent.MultiAgentModels.WorldBounds;
import com.betterlearning.server.multiagent.SourceTextBlocks;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class SourceAnchorValidatorTests {
    private static final String SOURCE = "1c6c965d-77a6-4b2e-86fd-bdefa9f8826a";
    private static final String FILE = "b03da72f-9990-43d8-9d92-1fe012bd3bea";
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

    private final AnswerFileStore files = mock(AnswerFileStore.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final SourceAnchorValidator validator = new SourceAnchorValidator(files, json);

    @Test
    void markdownAcceptsCrLfDigestButRejectsSurrogateSplitAndForgedDigest() {
        String text = "A😀\r\nB";
        String digest = AnswerManifestValidator.sha256("A😀\nB".getBytes(StandardCharsets.UTF_8));
        AnchorLocator locator = new AnchorLocator(1, new MarkdownLocator(0, 0, 3), null);
        validator.validate(anchor("MARKDOWN_BLOCK", digest, text, locator), note(text));
        assertInvalid(anchor("MARKDOWN_BLOCK", digest, text,
                new AnchorLocator(1, new MarkdownLocator(0, 0, 2), null)), note(text));
        assertInvalid(anchor("MARKDOWN_BLOCK", "0".repeat(64), text, locator), note(text));
    }

    @Test
    void strictJsonRejectsUrlUnknownFieldsMissingDiscriminatorAndSchema() {
        String text = "公式";
        String digest = AnswerManifestValidator.sha256(text.getBytes(StandardCharsets.UTF_8));
        assertInvalid(rawAnchor("MARKDOWN_BLOCK", digest, text,
                "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,"
                + "\"startOffset\":0,\"endOffset\":1,\"assetUrl\":\"https://attacker\"}}"), note(text));
        assertInvalid(rawAnchor("MARKDOWN_BLOCK", digest, text,
                "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,"
                + "\"startOffset\":0,\"endOffset\":1},\"url\":\"/files/private\"}"), note(text));
        assertInvalid(rawAnchor("MARKDOWN_BLOCK", digest, text,
                "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,\"endOffset\":1}}"), note(text));
        assertInvalid(anchor("MARKDOWN_BLOCK", digest, text,
                new AnchorLocator(2, new MarkdownLocator(0, 0, 1), null)), note(text));
    }

    @Test
    void markdownMustBeTheExactVersionBlockNotSelfConsistentForgedText() {
        String original = "真实原文";
        String forged = "伪造原文";
        String forgedDigest = AnswerManifestValidator.sha256(forged.getBytes(StandardCharsets.UTF_8));
        AnchorLocator locator = new AnchorLocator(1, new MarkdownLocator(0, 0, 2), null);
        assertInvalid(anchor("MARKDOWN_BLOCK", forgedDigest, forged, locator), note(original));
        assertInvalid(anchor("MARKDOWN_BLOCK", forgedDigest, forged, locator),
                typedContent(SourceType.SAVED_ANSWER, FILE, forged));
        assertInvalid(anchor("MARKDOWN_BLOCK", forgedDigest, forged, locator),
                typedContent(SourceType.TUTOR_SNAPSHOT, FILE, forged));
        assertInvalid(anchor("MARKDOWN_BLOCK", forgedDigest, forged, locator),
                typedContent(SourceType.NOTE, null, null));
    }

    @Test
    void markdownBlocksPreserveEmojiFormulaAndCodeFenceBlankLines() {
        String body = "A😀\r\nB\n\n$$\n\nx^2\n$$\n\n```md\n\ncode\n```";
        List<String> blocks = SourceTextBlocks.split(body);
        org.assertj.core.api.Assertions.assertThat(blocks).hasSize(3);
        org.assertj.core.api.Assertions.assertThat(blocks.get(0)).isEqualTo("A😀\nB");
        org.assertj.core.api.Assertions.assertThat(blocks.get(1)).isEqualTo("$$\n\nx^2\n$$");
        org.assertj.core.api.Assertions.assertThat(blocks.get(2)).isEqualTo("```md\n\ncode\n```");
        for (int ordinal = 0; ordinal < blocks.size(); ordinal++) {
            String text = ordinal == 0 ? "A😀\r\nB" : blocks.get(ordinal);
            String digest = AnswerManifestValidator.sha256(blocks.get(ordinal).getBytes(StandardCharsets.UTF_8));
            AnchorLocator locator = new AnchorLocator(1, new MarkdownLocator(ordinal, 0,
                    ordinal == 0 ? 3 : 2), null);
            validator.validate(anchor("MARKDOWN_BLOCK", digest, text, locator), note(body));
        }
    }

    @Test
    void handwritingMustMatchAuthorizedManifestGeometryAndRejectDuplicateSegments() throws Exception {
        AnswerModels.SaveManifest manifest = AnswerTestData.manifest(FILE, null);
        when(files.manifest(FILE)).thenReturn(new AnswerModels.StoredManifest(manifest,
                new AnswerModels.QuestionSnapshot("91", "OTHER", AnswerTestData.BODY)));
        when(files.block(FILE, "chunk_0000")).thenReturn(AnswerTestData.png(32));
        HandwritingSegment segment = new HandwritingSegment(0, false,
                new WorldBounds(0, 0, 32, 32), 32, 32, new WorldBounds(1, 2, 4, 5), List.of("s-1"));
        validator.validate(anchor("HANDWRITING_STEP", "0".repeat(64), null,
                new AnchorLocator(1, null, new HandwritingLocator(List.of(segment)))), content(FILE));
        assertInvalid(anchor("HANDWRITING_STEP", "0".repeat(64), null,
                new AnchorLocator(1, null, new HandwritingLocator(List.of(segment, segment)))), content(FILE));
        assertInvalid(anchor("HANDWRITING_STEP", "0".repeat(64), null,
                new AnchorLocator(1, null, new HandwritingLocator(List.of(new HandwritingSegment(1, false,
                segment.blockWorldBounds(), 32, 32, segment.regionWorldBounds(), null))))), content(FILE));
        assertInvalid(anchor("HANDWRITING_STEP", "0".repeat(64), null,
                new AnchorLocator(1, null, new HandwritingLocator(List.of(new HandwritingSegment(0, false,
                segment.blockWorldBounds(), 64, 64, segment.regionWorldBounds(), null))))), content(FILE));
        assertInvalid(anchor("HANDWRITING_STEP", "0".repeat(64), null,
                new AnchorLocator(1, null, new HandwritingLocator(List.of(new HandwritingSegment(0, true,
                segment.blockWorldBounds(), 32, 32, segment.regionWorldBounds(), null))))), content(FILE));
    }

    @Test
    void questionRasterUsesItsOwnPixelDensityAndNegativeWorldBounds() throws Exception {
        AnswerModels.SaveManifest base = AnswerTestData.manifest(FILE, null);
        AnswerModels.QuestionLayout old = base.document().questionLayout();
        AnswerModels.Block raster = new AnswerModels.Block(0, "question_0000", -8.0, -8.0,
                16.0, 16.0, 32, 32, AnswerManifestValidator.sha256(AnswerTestData.png(32)));
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout(old.questionBodySha256(),
                old.contentWidth(), old.fontSize(), old.rendererId(),
                new AnswerModels.Bounds(-8.0, -8.0, 16.0, 16.0),
                List.of(new AnswerModels.LayoutBlock("body-0", -8.0, -8.0, 16.0, 16.0)), List.of(raster));
        AnswerModels.SaveManifest manifest = new AnswerModels.SaveManifest(1, FILE, null, base.canvas(),
                new AnswerModels.Document(1, layout, base.document().strokes()), base.blocks());
        when(files.manifest(FILE)).thenReturn(new AnswerModels.StoredManifest(manifest,
                new AnswerModels.QuestionSnapshot("91", "OTHER", AnswerTestData.BODY)));
        when(files.block(FILE, "question_0000")).thenReturn(AnswerTestData.png(32));
        HandwritingSegment segment = new HandwritingSegment(0, true,
                new WorldBounds(-8, -8, 16, 16), 32, 32, new WorldBounds(-7, -7, 2, 2), null);
        assertInvalid(anchor("QUESTION_BLOCK", "0".repeat(64), "OCR内容",
                new AnchorLocator(1, null, new HandwritingLocator(List.of(segment)))), content(FILE));
        validator.validate(anchor("QUESTION_BLOCK", "0".repeat(64), null,
                new AnchorLocator(1, null, new HandwritingLocator(List.of(segment)))), content(FILE));
        assertInvalid(anchor("QUESTION_BLOCK", "0".repeat(64), null,
                new AnchorLocator(1, null, new HandwritingLocator(List.of(new HandwritingSegment(0, true,
                segment.blockWorldBounds(), 16, 16, segment.regionWorldBounds(), null))))), content(FILE));
        assertInvalid(anchor("QUESTION_BLOCK", "0".repeat(64), null,
                new AnchorLocator(1, null, new HandwritingLocator(List.of(new HandwritingSegment(0, true,
                        new WorldBounds(0, 0, 16, 16), 32, 32, segment.regionWorldBounds(), null))))), content(FILE));
    }

    private Anchor anchor(String kind, String digest, String text, AnchorLocator locator) {
        return rawAnchor(kind, digest, text, json.writeValueAsString(locator));
    }

    private static Anchor rawAnchor(String kind, String digest, String text, String raw) {
        return new Anchor("26b889d5-3f13-460f-8140-944520731834", SOURCE, 1, kind, 0, digest, raw, text);
    }

    private static SourceContent content(String fileRef) {
        return typedContent(SourceType.SAVED_ANSWER, fileRef, null);
    }

    private static SourceContent note(String body) {
        return typedContent(SourceType.NOTE, null, body);
    }

    private static SourceContent typedContent(SourceType type, String fileRef, String body) {
        Source source = new Source(SOURCE, 7, type, "91", "来源", "ACTIVE", 1, NOW);
        SourceVersion version = new SourceVersion(SOURCE, 1, "0".repeat(64), "READY", "DRAFT",
                fileRef, body, NOW);
        return new SourceContent(source, version, List.of());
    }

    private void assertInvalid(Anchor anchor, SourceContent content) {
        assertThatThrownBy(() -> validator.validate(anchor, content)).isInstanceOf(BusinessException.class);
    }
}
