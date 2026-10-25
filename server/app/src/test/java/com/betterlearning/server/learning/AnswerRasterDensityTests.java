package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;

import org.junit.jupiter.api.Test;

class AnswerRasterDensityTests {
    @Test
    void acceptsOldOneTimesAndNewTwoTimesQuestionRaster() {
        for (double density : List.of(1.0, 2.0)) {
            AnswerModels.SaveManifest manifest = manifest(1041, 1033, density);
            assertThatCode(() -> AnswerManifestValidator.validate(manifest, AnswerTestData.BODY)).doesNotThrowAnyException();
        }
    }

    @Test
    void acceptsUniformFractionalDensityAndOnePixelRoundingForNarrowOrShortEdges() {
        for (int[] size : List.of(new int[] {1025, 1025}, new int[] {1, 2049}, new int[] {2049, 1})) {
            AnswerModels.SaveManifest source = manifest(size[0], size[1], 1.5);
            List<AnswerModels.Block> rasters = new ArrayList<>(source.document().questionLayout().rasterBlocks());
            int last = rasters.size() - 1;
            AnswerModels.Block edge = rasters.get(last);
            rasters.set(last, pixels(edge, Math.max(edge.width().intValue(), edge.pixelWidth() - 1),
                    Math.max(edge.height().intValue(), edge.pixelHeight() - 1)));
            AnswerModels.SaveManifest rounded = rasters(source, rasters);
            assertThatCode(() -> AnswerManifestValidator.validate(rounded, AnswerTestData.BODY)).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsMixedDensityAxisMismatchAndMoreThanOnePixelRounding() {
        AnswerModels.SaveManifest source = manifest(2048, 1024, 2);
        List<AnswerModels.Block> original = source.document().questionLayout().rasterBlocks();
        for (AnswerModels.Block second : List.of(pixels(original.get(1), 1024, 1024),
                pixels(original.get(1), 2048, 1024), pixels(original.get(1), 2046, 2048))) {
            AnswerModels.SaveManifest bad = rasters(source, List.of(original.get(0), second));
            assertThatThrownBy(() -> AnswerManifestValidator.validate(bad, AnswerTestData.BODY))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void rejectsInvalidPixelSizesWithoutWeakeningLogicalGridOrCompositeRatio() {
        AnswerModels.SaveManifest source = manifest(16, 16, 2);
        AnswerModels.Block raster = source.document().questionLayout().rasterBlocks().get(0);
        for (Integer width : java.util.Arrays.asList(null, 0, 15, 33, Integer.MAX_VALUE)) {
            AnswerModels.SaveManifest bad = rasters(source, List.of(pixels(raster, width, 32)));
            assertThatThrownBy(() -> AnswerManifestValidator.validate(bad, AnswerTestData.BODY))
                    .isInstanceOf(BusinessException.class);
        }
        AnswerModels.Block chunk = source.blocks().get(0);
        AnswerModels.SaveManifest denseComposite = new AnswerModels.SaveManifest(1, source.saveRequestId(), null,
                source.canvas(), source.document(), List.of(pixels(chunk, 32, 32)));
        assertThatThrownBy(() -> AnswerManifestValidator.validate(denseComposite, AnswerTestData.BODY))
                .isInstanceOf(BusinessException.class);
        AnswerModels.Block moved = new AnswerModels.Block(raster.index(), raster.partName(), 1.0, raster.y(),
                raster.width(), raster.height(), raster.pixelWidth(), raster.pixelHeight(), raster.sha256());
        assertThatThrownBy(() -> AnswerManifestValidator.validate(rasters(source, List.of(moved)), AnswerTestData.BODY))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void countsActualQuestionAndCompositePixelsAtTheSixtyFourMillionBoundary() {
        // 3200*4000*(2*2+1) = 64,000,000；只按逻辑面积会低估四倍题面像素。
        AnswerModels.SaveManifest exact = manifest(3200, 4000, 2);
        assertThatCode(() -> AnswerManifestValidator.validate(exact, AnswerTestData.BODY)).doesNotThrowAnyException();
        AnswerModels.SaveManifest excess = manifest(3201, 4000, 2);
        assertThatThrownBy(() -> AnswerManifestValidator.validate(excess, AnswerTestData.BODY))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.UPLOAD_TOO_LARGE));
    }

    private AnswerModels.SaveManifest manifest(int width, int height, double density) {
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout(
                AnswerManifestValidator.sha256(AnswerTestData.BODY.getBytes(StandardCharsets.UTF_8)), 720.0, 18.0,
                "density-test", new AnswerModels.Bounds(0.0, 0.0, (double) width, (double) height),
                List.of(new AnswerModels.LayoutBlock("body", 0.0, 0.0, (double) width, (double) height)),
                grid("question_", width, height, density));
        return new AnswerModels.SaveManifest(1, UUID.randomUUID().toString(), null,
                new AnswerModels.Canvas((double) width, (double) height, 0.0, 0.0, "logical"),
                new AnswerModels.Document(1, layout, List.of()), grid("chunk_", width, height, 1));
    }

    private List<AnswerModels.Block> grid(String prefix, int width, int height, double density) {
        List<AnswerModels.Block> blocks = new ArrayList<>();
        for (int y = 0; y < height; y += 1024) {
            for (int x = 0; x < width; x += 1024) {
                int w = Math.min(1024, width - x);
                int h = Math.min(1024, height - y);
                int index = blocks.size();
                blocks.add(new AnswerModels.Block(index, prefix + String.format(java.util.Locale.ROOT, "%04d", index),
                        (double) x, (double) y, (double) w, (double) h,
                        (int) Math.round(w * density), (int) Math.round(h * density), "a".repeat(64)));
            }
        }
        return blocks;
    }

    private AnswerModels.Block pixels(AnswerModels.Block block, Integer width, Integer height) {
        return new AnswerModels.Block(block.index(), block.partName(), block.x(), block.y(), block.width(), block.height(),
                width, height, block.sha256());
    }

    private AnswerModels.SaveManifest rasters(AnswerModels.SaveManifest source, List<AnswerModels.Block> blocks) {
        AnswerModels.QuestionLayout layout = source.document().questionLayout();
        AnswerModels.QuestionLayout changed = new AnswerModels.QuestionLayout(layout.questionBodySha256(), layout.contentWidth(),
                layout.fontSize(), layout.rendererId(), layout.bounds(), layout.blocks(), blocks);
        return new AnswerModels.SaveManifest(1, source.saveRequestId(), source.baseFileRef(), source.canvas(),
                new AnswerModels.Document(1, changed, source.document().strokes()), source.blocks());
    }
}
