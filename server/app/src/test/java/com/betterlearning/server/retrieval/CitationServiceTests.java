package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.betterlearning.server.agent.retrieval.anchor.MarkdownAnchorParser;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.retrieval.mapper.CitationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;

class CitationServiceTests {
    private static final String CITATION_ID = "11111111-1111-4111-8111-111111111111";
    private static final String FILE_REF = "22222222-2222-4222-8222-222222222222";

    private final CitationMapper mapper = mock(CitationMapper.class);
    private final AnswerFileStore files = mock(AnswerFileStore.class);
    private final CitationService service = new CitationService(mapper, files);

    @BeforeEach
    void accountExists() {
        when(mapper.lockAccount(7)).thenReturn(7L);
    }

    @Test
    void readEndpointsHoldWriteTransactionForAccountLock() throws NoSuchMethodException {
        Transactional resolve = CitationService.class.getMethod("resolve", long.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional asset = CitationService.class.getMethod("asset", long.class, String.class, int.class)
                .getAnnotation(Transactional.class);
        assertThat(resolve).isNotNull();
        assertThat(asset).isNotNull();
        assertThat(resolve.readOnly()).isFalse();
        assertThat(asset.readOnly()).isFalse();
    }

    @Test
    void missingAuthorizationReturnsNotFoundWithoutReadingFiles() {
        when(mapper.authorized(7, CITATION_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(files);
        var order = inOrder(mapper);
        order.verify(mapper).lockAccount(7);
        order.verify(mapper).authorized(7, CITATION_ID);
    }

    @Test
    void missingAccountStopsBeforeAuthorizationQuery() {
        when(mapper.lockAccount(7)).thenReturn(null);
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(files);
        var order = inOrder(mapper);
        order.verify(mapper).lockAccount(7);
        order.verifyNoMoreInteractions();
    }

    @Test
    void resolvesExactMarkdownBlockAndRejectsSurrogateSplit() {
        String markdown = "思路😀：换元法";
        String hash = MarkdownAnchorParser.parse(markdown).get(0).contentSha256();
        Map<String, Object> row = row("MARKDOWN_BLOCK", hash);
        row.put("bodyMarkdown", markdown);
        row.put("anchorText", markdown);
        row.put("locatorJson", "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,"
                + "\"startOffset\":0,\"endOffset\":2}}");
        when(mapper.authorized(7, CITATION_ID)).thenReturn(row);
        CitationService.Resolution resolved = service.resolve(7, CITATION_ID);
        assertThat(resolved.markdown()).isEqualTo(markdown);
        assertThat(resolved.quotedText()).isEqualTo("思路");
        assertThat(resolved.sourceTitle()).isEqualTo("旧版本标题");
        assertThat(resolved.citation().version()).isEqualTo(2);
        assertThat(resolved.currentVersion()).isEqualTo(3);
        for (String sourceType : List.of("QUESTION", "TUTOR_REPLY")) {
            row.put("sourceType", sourceType);
            assertThat(service.resolve(7, CITATION_ID).markdown()).isEqualTo(markdown);
        }
        row.put("sourceType", "NOTE");
        row.put("locatorJson", "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,"
                + "\"startOffset\":2,\"endOffset\":3}}");
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
        row.put("locatorJson", "{\"blockOrdinal\":0,\"startOffset\":0,\"endOffset\":2}");
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
        verifyNoInteractions(files);
    }

    @Test
    void selfConsistentForgedQuoteAndHashCannotReplaceHistoricalSourceBlock() {
        String original = "旧版本原文：先配方";
        String forged = "伪造引文：已经掌握";
        Map<String, Object> row = row("MARKDOWN_BLOCK",
                MarkdownAnchorParser.parse(forged).get(0).contentSha256());
        row.put("bodyMarkdown", original);
        row.put("anchorText", forged);
        row.put("locatorJson", "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,"
                + "\"startOffset\":0,\"endOffset\":4}}");
        when(mapper.authorized(7, CITATION_ID)).thenReturn(row);
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
        verifyNoInteractions(files);
    }

    @Test
    void savedAnswerOcrCannotMasqueradeAsMarkdownSource() {
        String text = "OCR识别的旧作答";
        Map<String, Object> row = row("MARKDOWN_BLOCK", MarkdownAnchorParser.parse(text).get(0).contentSha256());
        row.put("sourceType", "SAVED_ANSWER");
        row.put("bodyMarkdown", text);
        row.put("anchorText", text);
        row.put("locatorJson", "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,"
                + "\"startOffset\":0,\"endOffset\":3}}");
        when(mapper.authorized(7, CITATION_ID)).thenReturn(row);
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
        verifyNoInteractions(files);
    }

    @Test
    void assetUsesOnlyReferencedBlockAndVerifiesOriginalHash() {
        byte[] png = new byte[24];
        System.arraycopy(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}, 0, png, 0, 8);
        System.arraycopy("IHDR".getBytes(StandardCharsets.US_ASCII), 0, png, 12, 4);
        ByteBuffer.wrap(png, 16, 4).putInt(100);
        ByteBuffer.wrap(png, 20, 4).putInt(100);
        AnswerModels.Block block = new AnswerModels.Block(0, "block_0", 0.0, 0.0,
                10.0, 10.0, 100, 100, sha256(png));
        AnswerModels.Stroke stroke = new AnswerModels.Stroke("stroke-1", "#000000", 1.0, List.of());
        AnswerModels.Document document = new AnswerModels.Document(1, null, List.of(stroke));
        AnswerModels.SaveManifest source = new AnswerModels.SaveManifest(1, CITATION_ID, null,
                new AnswerModels.Canvas(10.0, 10.0, 0.0, 0.0, "logical"), document, List.of(block));
        when(files.manifest(FILE_REF)).thenReturn(new AnswerModels.StoredManifest(source, null));
        when(files.block(FILE_REF, "block_0")).thenReturn(png);
        Map<String, Object> row = row("HANDWRITING_STEP", sha256("step".getBytes(StandardCharsets.UTF_8)));
        row.put("fileRef", FILE_REF);
        row.put("anchorText", "step");
        row.put("locatorJson", "{\"schemaVersion\":1,\"handwritingLocator\":{\"segments\":[{"
                + "\"blockIndex\":0,\"questionRaster\":false,"
                + "\"blockWorldBounds\":{\"x\":0,\"y\":0,\"width\":10,\"height\":10},"
                + "\"pixelWidth\":100,\"pixelHeight\":100,"
                + "\"regionWorldBounds\":{\"x\":1,\"y\":1,\"width\":2,\"height\":2},"
                + "\"strokeIds\":[\"stroke-1\"]}]}}");
        when(mapper.authorized(7, CITATION_ID)).thenReturn(row);
        assertThat(service.asset(7, CITATION_ID, 0)).isEqualTo(png);
        assertThat(service.resolve(7, CITATION_ID).handwritingLocator().segments().get(0).assetUrl())
                .isEqualTo("/api/v2/learning/citations/" + CITATION_ID + "/assets/0");
        assertThatThrownBy(() -> service.asset(7, CITATION_ID, 1))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        when(files.block(FILE_REF, "block_0")).thenReturn(new byte[] {1});
        assertThatThrownBy(() -> service.asset(7, CITATION_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
        when(files.block(FILE_REF, "block_0")).thenReturn(png);
        row.put("anchorText", "changed step");
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
        row.put("anchorText", "step");
        row.put("locatorJson", row.get("locatorJson").toString().replace("stroke-1", "missing-stroke"));
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
        AnswerModels.Block next = new AnswerModels.Block(1, "block_1", 10.0, 0.0,
                10.0, 10.0, 100, 100, sha256(png));
        AnswerModels.Stroke other = new AnswerModels.Stroke("stroke-2", "#000000", 1.0, List.of());
        AnswerModels.Document twoStrokes = new AnswerModels.Document(1, null, List.of(stroke, other));
        when(files.manifest(FILE_REF)).thenReturn(new AnswerModels.StoredManifest(
                new AnswerModels.SaveManifest(1, CITATION_ID, null,
                        new AnswerModels.Canvas(20.0, 10.0, 0.0, 0.0, "logical"), twoStrokes,
                List.of(block, next)), null));
        String first = "{\"blockIndex\":0,\"questionRaster\":false,"
                + "\"blockWorldBounds\":{\"x\":0,\"y\":0,\"width\":10,"
                + "\"height\":10},\"pixelWidth\":100,\"pixelHeight\":100,"
                + "\"regionWorldBounds\":{\"x\":8,\"y\":1,\"width\":2,\"height\":2},"
                + "\"strokeIds\":[\"stroke-1\"]}";
        String second = "{\"blockIndex\":1,\"questionRaster\":false,"
                + "\"blockWorldBounds\":{\"x\":10,\"y\":0,\"width\":10,"
                + "\"height\":10},\"pixelWidth\":100,\"pixelHeight\":100,"
                + "\"regionWorldBounds\":{\"x\":10,\"y\":1,\"width\":2,\"height\":2},"
                + "\"strokeIds\":[\"stroke-2\"]}";
        row.put("locatorJson", "{\"schemaVersion\":1,\"handwritingLocator\":{\"segments\":["
                + first + "," + second + "]}}");
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
        row.put("locatorJson", "{\"schemaVersion\":1,\"handwritingLocator\":{\"segments\":["
                + first + "," + second.replace(",\"strokeIds\":[\"stroke-2\"]", "") + "]}}");
        assertThat(service.resolve(7, CITATION_ID).handwritingLocator().segments()).hasSize(2);
    }

    @Test
    void compositeBlockUsesCanvasOriginForWorldLocator() {
        byte[] png = new byte[24];
        System.arraycopy(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}, 0, png, 0, 8);
        System.arraycopy("IHDR".getBytes(StandardCharsets.US_ASCII), 0, png, 12, 4);
        ByteBuffer.wrap(png, 16, 4).putInt(10);
        ByteBuffer.wrap(png, 20, 4).putInt(10);
        AnswerModels.Block local = new AnswerModels.Block(0, "chunk_0000", 0.0, 0.0,
                10.0, 10.0, 10, 10, sha256(png));
        AnswerModels.SaveManifest source = new AnswerModels.SaveManifest(1, CITATION_ID, null,
                new AnswerModels.Canvas(10.0, 10.0, -10.0, -5.0, "logical"),
                new AnswerModels.Document(1, null, List.of()), List.of(local));
        when(files.manifest(FILE_REF)).thenReturn(new AnswerModels.StoredManifest(source, null));
        when(files.block(FILE_REF, "chunk_0000")).thenReturn(png);
        Map<String, Object> row = row("HANDWRITING_STEP", sha256("step".getBytes(StandardCharsets.UTF_8)));
        row.put("fileRef", FILE_REF);
        row.put("anchorText", "step");
        row.put("locatorJson", "{\"schemaVersion\":1,\"handwritingLocator\":{\"segments\":[{"
                + "\"blockIndex\":0,\"questionRaster\":false,"
                + "\"blockWorldBounds\":{\"x\":-10,\"y\":-5,\"width\":10,\"height\":10},"
                + "\"pixelWidth\":10,\"pixelHeight\":10,"
                + "\"regionWorldBounds\":{\"x\":-9,\"y\":-4,\"width\":2,\"height\":2}}]}}");
        when(mapper.authorized(7, CITATION_ID)).thenReturn(row);
        assertThat(service.resolve(7, CITATION_ID).handwritingLocator().segments().get(0)
                .blockWorldBounds().x()).isEqualTo(-10);
        assertThat(service.asset(7, CITATION_ID, 0)).isEqualTo(png);
        row.put("locatorJson", row.get("locatorJson").toString()
                .replace("\"x\":-10,\"y\":-5", "\"x\":0,\"y\":0"));
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode())
                .isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
    }

    @Test
    void questionRasterSelectsOriginalRasterBlockAndRejectsKindMismatch() {
        byte[] png = new byte[24];
        System.arraycopy(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}, 0, png, 0, 8);
        System.arraycopy("IHDR".getBytes(StandardCharsets.US_ASCII), 0, png, 12, 4);
        ByteBuffer.wrap(png, 16, 4).putInt(100);
        ByteBuffer.wrap(png, 20, 4).putInt(100);
        AnswerModels.Block raster = new AnswerModels.Block(0, "question_0000", 0.0, 0.0,
                10.0, 10.0, 100, 100, sha256(png));
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout(null, null, null,
                null, null, List.of(), List.of(raster));
        AnswerModels.Document document = new AnswerModels.Document(1, layout, List.of());
        AnswerModels.SaveManifest source = new AnswerModels.SaveManifest(1, CITATION_ID, null,
                new AnswerModels.Canvas(20.0, 20.0, -10.0, -10.0, "logical"), document, List.of());
        when(files.manifest(FILE_REF)).thenReturn(new AnswerModels.StoredManifest(source, null));
        when(files.block(FILE_REF, "question_0000")).thenReturn(png);
        // P1 accepts a raster anchor with no OCR text and an opaque 64-hex anchor digest.
        Map<String, Object> row = row("QUESTION_BLOCK", "0".repeat(64));
        row.put("sourceType", "TUTOR_SNAPSHOT");
        row.put("fileRef", FILE_REF);
        String locator = "{\"schemaVersion\":1,\"handwritingLocator\":{\"segments\":[{"
                + "\"blockIndex\":0,\"questionRaster\":true,"
                + "\"blockWorldBounds\":{\"x\":0,\"y\":0,\"width\":10,\"height\":10},"
                + "\"pixelWidth\":100,\"pixelHeight\":100,"
                + "\"regionWorldBounds\":{\"x\":1,\"y\":1,\"width\":2,\"height\":2}}]}}";
        row.put("locatorJson", locator);
        when(mapper.authorized(7, CITATION_ID)).thenReturn(row);
        assertThat(service.asset(7, CITATION_ID, 0)).isEqualTo(png);
        assertThat(service.resolve(7, CITATION_ID).handwritingLocator().segments().get(0).assetUrl())
                .isEqualTo("/api/v2/learning/citations/" + CITATION_ID + "/assets/0");
        row.put("anchorText", "伪造 OCR 原文");
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode())
                .isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
        row.remove("anchorText");
        row.put("sourceType", "QUESTION");
        assertThatThrownBy(() -> service.asset(7, CITATION_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode())
                .isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
        row.put("sourceType", "TUTOR_SNAPSHOT");
        row.put("locatorJson", locator.replace("\"questionRaster\":true",
                "\"questionRaster\":true,\"assetUrl\":\"https://wrong.invalid\""));
        assertThatThrownBy(() -> service.resolve(7, CITATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
        row.put("locatorJson", locator);
        row.put("anchorKind", "HANDWRITING_STEP");
        assertThatThrownBy(() -> service.asset(7, CITATION_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
    }

    private static Map<String, Object> row(String kind, String hash) {
        Map<String, Object> row = new HashMap<>();
        row.put("citationId", CITATION_ID);
        row.put("sourceId", "33333333-3333-4333-8333-333333333333");
        row.put("version", 2L);
        row.put("anchorId", "44444444-4444-4444-8444-444444444444");
        row.put("label", "引用 1");
        row.put("sourceType", "NOTE");
        row.put("citationSha256", hash);
        row.put("anchorKind", kind);
        row.put("anchorOrdinal", 0);
        row.put("anchorSha256", hash);
        row.put("currentVersion", 3L);
        row.put("sourceTitle", "旧版本标题");
        row.put("sourceCreatedAt", Instant.parse("2026-10-01T00:00:00Z"));
        row.put("sourceVersionCreatedAt", Instant.parse("2026-10-02T00:00:00Z"));
        return row;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
