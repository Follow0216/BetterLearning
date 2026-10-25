package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.config.AppFilesProperties;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.learning.validation.SourceAnchorValidator;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.AnchorLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.HandwritingLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.HandwritingSegment;
import com.betterlearning.server.multiagent.MultiAgentModels.Source;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceVersion;
import com.betterlearning.server.multiagent.MultiAgentModels.WorldBounds;

import jakarta.servlet.http.Part;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;

import tools.jackson.databind.json.JsonMapper;

/** 使用真实落盘文件验证合成块相对坐标与题面栅格世界坐标。 */
class SourceAnchorRealFileTests {
    private static final String BODY = "负坐标与跨块题面";
    private static final String SOURCE_ID = "291b9e32-7d39-4d95-aefe-464e56e36898";
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

    @TempDir
    Path directory;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void realPackageAcceptsFourWaySplitAndQuestionRasterWithoutDoubleOrigin() throws Exception {
        String saveId = UUID.randomUUID().toString();
        byte[][] composite = {png(1024, 1024), png(8, 1024), png(1024, 16), png(8, 16)};
        byte[] questionPng = png(48, 40);
        AnswerModels.SaveManifest manifest = manifest(saveId, composite, questionPng);
        AnswerManifestValidator.validate(manifest, BODY);
        AnswerFileStore files = store();
        AnswerModels.QuestionSnapshot question = new AnswerModels.QuestionSnapshot("91", "OTHER", BODY);
        AnswerFileStore.Candidate candidate = files.save(manifest, question, parts(manifest, composite, questionPng));
        assertThat(files.manifest(candidate.fileRef()).source()).isEqualTo(manifest);
        SourceAnchorValidator validator = new SourceAnchorValidator(files, json);
        SourceContent content = content(candidate.fileRef());

        List<HandwritingSegment> split = List.of(
                segment(0, false, -16, -24, 1024, 1024, 1024, 1024, 1006, 998, 2, 2),
                segment(1, false, 1008, -24, 8, 1024, 8, 1024, 1008, 998, 4, 2),
                segment(2, false, -16, 1000, 1024, 16, 1024, 16, 1006, 1000, 2, 6),
                segment(3, false, 1008, 1000, 8, 16, 8, 16, 1008, 1000, 4, 6));
        validator.validate(anchor("HANDWRITING_STEP", split, "s-cross"), content);
        assertThat(files.block(candidate.fileRef(), "chunk_0003")).isEqualTo(composite[3]);

        HandwritingSegment crossingWithoutSplit = segment(0, false, -16, -24, 1024, 1024,
                1024, 1024, 1006, 998, 6, 8);
        assertThatThrownBy(() -> validator.validate(anchor("HANDWRITING_STEP",
                List.of(crossingWithoutSplit), "s-cross"), content)).isInstanceOf(BusinessException.class);

        HandwritingSegment raster = segment(0, true, -8, -12, 24, 20,
                48, 40, -6, -10, 3, 3);
        validator.validate(anchor("QUESTION_BLOCK", List.of(raster), null), content);
        assertThat(files.block(candidate.fileRef(), "question_0000")).isEqualTo(questionPng);
        HandwritingSegment doubleOrigin = segment(0, true, -24, -36, 24, 20,
                48, 40, -22, -34, 3, 3);
        assertThatThrownBy(() -> validator.validate(anchor("QUESTION_BLOCK",
                List.of(doubleOrigin), null), content)).isInstanceOf(BusinessException.class);
    }

    private AnswerModels.SaveManifest manifest(String saveId, byte[][] composite, byte[] questionPng) {
        List<AnswerModels.Block> blocks = List.of(
                block(0, "chunk_0000", 0, 0, 1024, 1024, 1024, 1024, composite[0]),
                block(1, "chunk_0001", 1024, 0, 8, 1024, 8, 1024, composite[1]),
                block(2, "chunk_0002", 0, 1024, 1024, 16, 1024, 16, composite[2]),
                block(3, "chunk_0003", 1024, 1024, 8, 16, 8, 16, composite[3]));
        AnswerModels.Block raster = block(0, "question_0000", -8, -12, 24, 20, 48, 40, questionPng);
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout(
                AnswerManifestValidator.sha256(BODY.getBytes(StandardCharsets.UTF_8)), 24.0, 16.0,
                "renderer-v1", new AnswerModels.Bounds(-8.0, -12.0, 24.0, 20.0),
                List.of(new AnswerModels.LayoutBlock("question-body", -8.0, -12.0, 24.0, 20.0)),
                List.of(raster));
        AnswerModels.Stroke stroke = new AnswerModels.Stroke("s-cross", "#152744", 2.0,
                List.of(new AnswerModels.Point(1007.0, 999.0, null),
                        new AnswerModels.Point(1010.0, 1003.0, 0.5)));
        return new AnswerModels.SaveManifest(1, saveId, null,
                new AnswerModels.Canvas(1032.0, 1040.0, -16.0, -24.0, "logical"),
                new AnswerModels.Document(1, layout, List.of(stroke)), blocks);
    }

    private List<Part> parts(AnswerModels.SaveManifest manifest, byte[][] composite,
            byte[] questionPng) {
        List<Part> parts = new ArrayList<>();
        parts.add(part("manifest", json.writeValueAsBytes(manifest), MediaType.APPLICATION_JSON));
        for (int index = 0; index < composite.length; index++) {
            parts.add(part(manifest.blocks().get(index).partName(), composite[index], MediaType.IMAGE_PNG));
        }
        parts.add(part("question_0000", questionPng, MediaType.IMAGE_PNG));
        return parts;
    }

    private AnswerFileStore store() {
        AppFilesProperties properties = new AppFilesProperties();
        properties.setRoot(directory.toString());
        return new AnswerFileStore(properties, json);
    }

    private Anchor anchor(String kind, List<HandwritingSegment> segments, String strokeId) {
        List<HandwritingSegment> located = segments.stream()
                .map(segment -> new HandwritingSegment(segment.blockIndex(), segment.questionRaster(),
                segment.blockWorldBounds(), segment.pixelWidth(), segment.pixelHeight(),
                segment.regionWorldBounds(), strokeId == null ? null : List.of(strokeId))).toList();
        AnchorLocator locator = new AnchorLocator(1, null, new HandwritingLocator(located));
        return new Anchor(UUID.randomUUID().toString(), SOURCE_ID, 1, kind, 0,
                "0".repeat(64), json.writeValueAsString(locator), null);
    }

    private static SourceContent content(String fileRef) {
        Source source = new Source(SOURCE_ID, 7, SourceType.SAVED_ANSWER, "91", "作答", "ACTIVE", 1, NOW);
        SourceVersion version = new SourceVersion(SOURCE_ID, 1, "0".repeat(64), "READY", "DRAFT",
                fileRef, null, NOW);
        return new SourceContent(source, version, List.of());
    }

    private static HandwritingSegment segment(int index, boolean raster, double blockX, double blockY,
            double blockWidth, double blockHeight, int pixelWidth, int pixelHeight,
            double regionX, double regionY, double regionWidth, double regionHeight) {
        return new HandwritingSegment(index, raster,
                new WorldBounds(blockX, blockY, blockWidth, blockHeight), pixelWidth, pixelHeight,
                new WorldBounds(regionX, regionY, regionWidth, regionHeight), null);
    }

    private static AnswerModels.Block block(int index, String name, double x, double y,
            double width, double height, int pixelWidth, int pixelHeight, byte[] image) {
        return new AnswerModels.Block(index, name, x, y, width, height, pixelWidth, pixelHeight,
                AnswerManifestValidator.sha256(image));
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff152744);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        image.flush();
        return bytes.toByteArray();
    }

    private static Part part(String name, byte[] bytes, MediaType type) {
        MockPart part = new MockPart(name, name, bytes);
        part.getHeaders().setContentType(type);
        return part;
    }
}
