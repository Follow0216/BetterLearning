package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Job;
import com.betterlearning.server.agent.service.OcrModel;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.Source;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceVersion;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.retrieval.HandwritingSourceReader.HandwritingReadException;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HandwritingSourceReaderTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final String fileRef = "00000000-0000-0000-0000-000000000001";

    @Test
    void cropsCrossBlockNegativeWorldStepAndRetainsOriginalSegments() throws Exception {
        byte[] left = png(1024, 10, Color.RED);
        byte[] right = png(10, 10, Color.BLUE);
        AnswerModels.Block first = block(0, "chunk_0000", -1024, 0, 1024, 10, 1024, 10, left);
        AnswerModels.Block second = block(1, "chunk_0001", 0, 0, 10, 10, 10, 10, right);
        AnswerFileStore store = store(List.of(stroke("s1", -1, 2, 1, 2)),
                List.of(first, second), List.of(left, right));
        List<BufferedImage> received = new ArrayList<>();
        OcrModel ocr = image -> {
            received.add(decode(image));
            return "用配方法完成平方";
        };
        HandwritingSourceReader.ParsedHandwriting parsed = new HandwritingSourceReader(store, ocr, json)
                .read(fileRef);

        assertThat(parsed.ocrCalls()).isEqualTo(1);
        assertThat(parsed.anchors()).hasSize(1);
        HandwritingSourceReader.AnchorDraft anchor = parsed.anchors().get(0);
        assertThat(anchor.kind()).isEqualTo("HANDWRITING_STEP");
        assertThat(anchor.ordinal()).isZero();
        assertThat(anchor.contentSha256()).isEqualTo(AnswerManifestValidator.sha256(
                anchor.text().getBytes(StandardCharsets.UTF_8)));
        JsonNode locator = json.readTree(anchor.locatorJson());
        assertThat(locator.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(locator.path("markdownLocator").isMissingNode()
                || locator.path("markdownLocator").isNull()).isTrue();
        JsonNode segments = locator.path("handwritingLocator").path("segments");
        assertThat(segments.size()).isEqualTo(2);
        assertThat(segments.get(0).path("questionRaster").booleanValue()).isFalse();
        assertThat(segments.get(0).path("blockWorldBounds").path("x").doubleValue()).isEqualTo(-1024);
        assertThat(segments.get(1).path("blockWorldBounds").path("x").doubleValue()).isZero();
        assertThat(segments.get(0).has("assetUrl")).isFalse();
        assertThat(segments.get(0).path("regionWorldBounds").path("x").doubleValue())
                .isLessThan(0);
        assertThat(segments.get(1).path("pixelWidth").intValue()).isEqualTo(10);
        assertThat(segments.get(0).path("strokeIds").get(0).textValue()).isEqualTo("s1");
        assertThat(received).hasSize(1);
        BufferedImage crop = received.get(0);
        assertThat(crop.getRGB(crop.getWidth() / 4, crop.getHeight() / 2))
                .isEqualTo(Color.RED.getRGB());
        assertThat(crop.getRGB(crop.getWidth() * 3 / 4, crop.getHeight() / 2))
                .isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void prepareProducesBoundedImmutableCropsWithoutCallingOcr() throws Exception {
        byte[] image = png(10, 10, Color.BLACK);
        AnswerModels.Block block = block(0, "chunk_0000", -10, 0, 10, 10, 10, 10, image);
        AnswerFileStore store = store(List.of(stroke("s1", -8, 2, -7, 2)),
                List.of(block), List.of(image));
        OcrModel ocr = mock(OcrModel.class);
        HandwritingSourceReader reader = new HandwritingSourceReader(store, ocr, json);

        List<HandwritingSourceReader.PreparedStep> prepared = reader.prepare(fileRef);
        verifyNoInteractions(ocr);
        assertThat(prepared).hasSize(1);
        var step = prepared.get(0);
        assertThat(step.ordinal()).isZero();
        assertThat(step.image().mediaType()).isEqualTo("image/png");
        assertThat(step.imageSha256()).isEqualTo(AnswerManifestValidator.sha256(step.image().bytes()));
        assertThat(decode(step.image()).getWidth()).isEqualTo(step.pixelWidth());
        assertThat(decode(step.image()).getHeight()).isEqualTo(step.pixelHeight());
        assertThat(json.readTree(step.locatorJson()).path("handwritingLocator").path("segments").size())
                .isEqualTo(1);
        HandwritingSourceReader.AnchorDraft draft = reader.anchorDraft(step, "第一步\r\n配方");
        assertThat(draft.ordinal()).isZero();
        assertThat(draft.text()).isEqualTo("第一步\n配方");
        assertThat(draft.locatorJson()).isEqualTo(step.locatorJson());
        assertThat(draft.contentSha256()).isEqualTo(AnswerManifestValidator.sha256(
                draft.text().getBytes(StandardCharsets.UTF_8)));
        verifyNoInteractions(ocr);
    }

    @Test
    void groupsSeparatedRowsWithoutCallingOcrForWholeQuestion() throws Exception {
        byte[] image = png(10, 10, Color.BLACK);
        AnswerModels.Block block = block(0, "chunk_0000", -10, 0, 10, 10, 10, 10, image);
        AnswerFileStore store = store(List.of(stroke("first", -8, 2, -7, 2),
                stroke("second", -8, 7, -7, 7)), List.of(block), List.of(image));
        List<ModelImage> received = new ArrayList<>();
        OcrModel ocr = input -> {
            received.add(input);
            return "步骤" + received.size();
        };
        HandwritingSourceReader.ParsedHandwriting parsed = new HandwritingSourceReader(store, ocr, json)
                .read(fileRef);
        assertThat(parsed.anchors()).hasSize(2);
        assertThat(parsed.ocrCalls()).isEqualTo(2);
        assertThat(received).hasSize(2);
    }

    @Test
    void rejectsInvalidManifestBeforeOcr() throws Exception {
        byte[] left = png(10, 10, Color.RED);
        AnswerModels.Block block = block(0, "chunk_0000", -10, 0, 10, 10, 10, 10, left);
        AnswerFileStore store = store(List.of(stroke("s1", -1, 2, 1, 2)),
                List.of(block), List.of(left));
        OcrModel ocr = input -> {
            throw new AssertionError("OCR must not be called");
        };
        assertThatThrownBy(() -> new HandwritingSourceReader(store, ocr, json).read(fileRef))
                .isInstanceOfSatisfying(HandwritingReadException.class, error -> {
                    assertThat(error.reason()).isEqualTo("INVALID_MANIFEST");
                    assertThat(error.ocrCalls()).isZero();
                });
    }

    @Test
    void missingOcrAndExcessStepsFailVisibly() throws Exception {
        byte[] image = png(10, 100, Color.BLACK);
        AnswerModels.Block block = block(0, "chunk_0000", 0, 0, 10, 100, 10, 100, image);
        AnswerFileStore oneStep = store(List.of(stroke("s1", 1, 2, 2, 2)),
                List.of(block), List.of(image));
        assertThat(new HandwritingSourceReader(oneStep, null, json).prepare(fileRef)).hasSize(1);
        assertThatThrownBy(() -> new HandwritingSourceReader(oneStep, null, json).read(fileRef))
                .isInstanceOfSatisfying(HandwritingReadException.class, error -> {
                    assertThat(error.reason()).isEqualTo("OCR_UNAVAILABLE");
                    assertThat(error.ocrCalls()).isZero();
                });
        List<AnswerModels.Stroke> strokes = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            strokes.add(stroke("s" + index, 1, 3 + index * 5, 2, 3 + index * 5));
        }
        AnswerFileStore tooMany = store(strokes, List.of(block), List.of(image));
        assertThatThrownBy(() -> new HandwritingSourceReader(tooMany, input -> "unused", json)
                .read(fileRef)).isInstanceOfSatisfying(HandwritingReadException.class, error -> {
                    assertThat(error.reason()).isEqualTo("TOO_MANY_STEPS");
                    assertThat(error.ocrCalls()).isZero();
                });
    }

    @Test
    void reuseChecksAllOriginalImagesAndDoesNotCallOcrAgain() throws Exception {
        String body = "题";
        byte[] composite = png(10, 10, Color.RED);
        byte[] raster = png(10, 10, Color.WHITE);
        AnswerModels.Block written = block(0, "chunk_0000", 0, 0, 10, 10, 10, 10, composite);
        AnswerModels.Block clean = block(0, "question_0000", 0, 0, 10, 10, 10, 10, raster);
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout(
                AnswerManifestValidator.sha256(body.getBytes(StandardCharsets.UTF_8)), 10.0, 12.0,
                "local-markdown-v1", new AnswerModels.Bounds(0.0, 0.0, 10.0, 10.0),
                List.of(new AnswerModels.LayoutBlock("body-0", 0.0, 0.0, 10.0, 10.0)), List.of(clean));
        AnswerModels.SaveManifest manifest = new AnswerModels.SaveManifest(1,
                UUID.randomUUID().toString(), null,
                new AnswerModels.Canvas(10.0, 10.0, 0.0, 0.0, "logical"),
                new AnswerModels.Document(1, layout, List.of(stroke("s1", 2, 2, 3, 2))),
                List.of(written));
        AnswerFileStore store = mock(AnswerFileStore.class);
        when(store.manifest(fileRef)).thenReturn(new AnswerModels.StoredManifest(manifest,
                new AnswerModels.QuestionSnapshot("1", "OTHER", body)));
        when(store.block(fileRef, "chunk_0000")).thenReturn(composite);
        AtomicInteger calls = new AtomicInteger();
        HandwritingSourceReader reader = new HandwritingSourceReader(store, image -> {
            calls.incrementAndGet();
            return "步骤";
        }, json);
        HandwritingSourceReader.AnchorDraft draft = reader.read(fileRef).anchors().get(0);
        String sourceId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-10-25T00:00:00Z");
        Source source = new Source(sourceId, 7, SourceType.SAVED_ANSWER, "1", "作答", "ACTIVE", 1, now);
        SourceVersion version = new SourceVersion(sourceId, 1, "a".repeat(64), "INDEX_PENDING",
                "DRAFT", fileRef, null, now, 2);
        Anchor anchor = new Anchor(UUID.randomUUID().toString(), sourceId, 1, draft.kind(),
                draft.ordinal(), draft.contentSha256(), draft.locatorJson(), draft.text());
        SourceContent content = new SourceContent(source, version, List.of(anchor));

        reader.validateReuse(content);
        assertThat(calls.get()).isEqualTo(1);

        when(store.block(fileRef, "chunk_0000")).thenReturn(png(10, 10, Color.BLUE));
        assertThatThrownBy(() -> reader.validateReuse(content))
                .isInstanceOfSatisfying(HandwritingReadException.class, error -> {
                    assertThat(error.reason()).isEqualTo("SOURCE_REUSE_INVALID");
                    assertThat(error.ocrCalls()).isZero();
                });
        assertThat(calls.get()).isEqualTo(1);

        SourceStore sources = mock(SourceStore.class);
        RetrievalIndexStore indexes = mock(RetrievalIndexStore.class);
        HttpEmbeddingClient embedding = mock(HttpEmbeddingClient.class);
        Job job = new Job("event-1", 7, sourceId, 1, "SOURCE_REINDEX", 1, 2);
        when(sources.get(7, sourceId, 1)).thenReturn(content);
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setEnabled(true);
        properties.setModel("synthetic-mechanism-only");
        properties.setDimensions(2);
        new RetrievalIndexWorker(sources, indexes, embedding, properties, json, reader).process(job);
        verify(indexes).fail(job, "HANDWRITING_SOURCE_REUSE_INVALID");
        verifyNoInteractions(embedding);
        assertThat(calls.get()).isEqualTo(1);
    }

    private AnswerFileStore store(List<AnswerModels.Stroke> strokes,
            List<AnswerModels.Block> blocks, List<byte[]> images) {
        AnswerFileStore store = mock(AnswerFileStore.class);
        String body = "题";
        // Test inputs use world positions; the saved composite block grid is canvas-local.
        double originX = blocks.stream().mapToDouble(AnswerModels.Block::x).min().orElseThrow();
        double originY = blocks.stream().mapToDouble(AnswerModels.Block::y).min().orElseThrow();
        double width = blocks.stream().mapToDouble(block -> block.x() + block.width()).max().orElseThrow() - originX;
        double height = blocks.stream().mapToDouble(block -> block.y() + block.height()).max().orElseThrow() - originY;
        List<AnswerModels.Block> local = blocks.stream().map(block -> new AnswerModels.Block(block.index(),
                block.partName(), block.x() - originX, block.y() - originY, block.width(), block.height(),
                block.pixelWidth(), block.pixelHeight(), block.sha256())).toList();
        byte[] raster = pngUnchecked(10, 10, Color.WHITE);
        AnswerModels.Block clean = block(0, "question_0000", originX, originY,
                10, 10, 10, 10, raster);
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout(
                AnswerManifestValidator.sha256(body.getBytes(StandardCharsets.UTF_8)), 10.0, 12.0,
                "local-markdown-v1", new AnswerModels.Bounds(originX, originY, 10.0, 10.0),
                List.of(new AnswerModels.LayoutBlock("body-0", originX, originY, 10.0, 10.0)),
                List.of(clean));
        AnswerModels.Document document = new AnswerModels.Document(1, layout, strokes);
        AnswerModels.SaveManifest manifest = new AnswerModels.SaveManifest(1,
                UUID.randomUUID().toString(), null,
                new AnswerModels.Canvas(width, height, originX, originY, "logical"), document, local);
        when(store.manifest(fileRef)).thenReturn(new AnswerModels.StoredManifest(manifest,
                new AnswerModels.QuestionSnapshot("1", "OTHER", body)));
        when(store.block(fileRef, "question_0000")).thenReturn(raster);
        for (int index = 0; index < blocks.size(); index++) {
            when(store.block(fileRef, blocks.get(index).partName())).thenReturn(images.get(index));
        }
        return store;
    }

    private static byte[] pngUnchecked(int width, int height, Color color) {
        try {
            return png(width, height, color);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AnswerModels.Stroke stroke(String id, double x1, double y1,
            double x2, double y2) {
        return new AnswerModels.Stroke(id, "#000000", 1.0,
                List.of(new AnswerModels.Point(x1, y1), new AnswerModels.Point(x2, y2)));
    }

    private static AnswerModels.Block block(int index, String partName, double x, double y,
            double width, double height, int pixelWidth, int pixelHeight, byte[] bytes) {
        return new AnswerModels.Block(index, partName, x, y, width, height,
                pixelWidth, pixelHeight, AnswerManifestValidator.sha256(bytes));
    }

    private static byte[] png(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        image.flush();
        return output.toByteArray();
    }

    private static BufferedImage decode(ModelImage image) {
        try {
            return ImageIO.read(new ByteArrayInputStream(image.bytes()));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
