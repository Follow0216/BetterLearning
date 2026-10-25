package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.validation.NoteContent;

import org.junit.jupiter.api.Test;

class NoteContentTests {
    @Test
    void countsNormalizedUtf8BytesAtExactLimitIncludingMultibyteText() {
        String exact = "中".repeat(21845) + "a";
        NoteContent content = NoteContent.validate("标题", exact);
        assertThat(content.bodyMarkdown().getBytes(StandardCharsets.UTF_8)).hasSize(65536);
        assertThatThrownBy(() -> NoteContent.validate("标题", exact + "😀"))
                .isInstanceOf(BusinessException.class);
        assertThat(NoteContent.validate("标题", "a\r\n".repeat(32768)).bodyMarkdown()
                .getBytes(StandardCharsets.UTF_8)).hasSize(65536);
    }

    @Test
    void normalizesOnlyCrLfAndPreservesFormulaSpacesAndEmoji() throws Exception {
        NoteContent content = NoteContent.validate("标题  ", "  $$\r\n\\frac{😀}{2}  \r\n$$  \r原样");
        assertThat(content.bodyMarkdown()).isEqualTo("  $$\n\\frac{😀}{2}  \n$$  \r原样");
        assertThat(content.contentSha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.bodyMarkdown().getBytes(StandardCharsets.UTF_8))));
        assertThat(NoteContent.validate("标题  ", "  $$\n\\frac{😀}{2}  \n$$  \r原样").contentSha256())
                .isEqualTo(content.contentSha256());
    }

    @Test
    void rejectsUnpairedSurrogatesAndIncludesTitleInRequestFingerprint() {
        assertThatThrownBy(() -> NoteContent.validate("\ud83d", "正文"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> NoteContent.validate("标题", "正文\ude00"))
                .isInstanceOf(BusinessException.class);
        NoteContent original = NoteContent.validate("标题", "正文");
        NoteContent renamed = NoteContent.validate("改名", "正文");
        assertThat(original.contentSha256()).isEqualTo(renamed.contentSha256());
        assertThat(original.requestSha256()).isNotEqualTo(renamed.requestSha256());
        assertThat(NoteContent.validate("长".repeat(513), "正文").title()).hasSize(513);
    }

    @Test
    void acceptsOnlyMatchingStrongEtagForSameNoteAndVersion() {
        String noteId = UUID.randomUUID().toString();
        String otherId = UUID.randomUUID().toString();
        String etag = NoteContent.etag(noteId, 2);
        assertThat(NoteContent.matches(etag, noteId, 2)).isTrue();
        assertThat(NoteContent.matches("W/" + etag, noteId, 2)).isFalse();
        assertThat(NoteContent.matches("*", noteId, 2)).isFalse();
        assertThat(NoteContent.matches(NoteContent.etag(otherId, 2), noteId, 2)).isFalse();
        assertThat(NoteContent.matches(NoteContent.etag(noteId, 1), noteId, 2)).isFalse();
    }
}
