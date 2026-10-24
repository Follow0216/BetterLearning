package com.betterlearning.server.learning;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.imageio.ImageIO;

import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;

final class AnswerTestData {
    static final String BODY = "求导 x²";

    private AnswerTestData() {
    }

    static AnswerModels.SaveManifest manifest(String requestId, String baseRef) throws IOException {
        AnswerModels.Block composite = block("chunk_0000", 32, png(32));
        AnswerModels.Block raster = block("question_0000", 16, png(16));
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout(
                AnswerManifestValidator.sha256(BODY.getBytes(StandardCharsets.UTF_8)), 16.0, 16.0,
                "local-markdown-v1", new AnswerModels.Bounds(0.0, 0.0, 16.0, 16.0),
                List.of(new AnswerModels.LayoutBlock("body-0", 0.0, 0.0, 16.0, 16.0)), List.of(raster));
        AnswerModels.Stroke stroke = new AnswerModels.Stroke("s-1", "#152744", 2.0,
                List.of(new AnswerModels.Point(-2.0, -2.0), new AnswerModels.Point(1.0, 1.0)));
        return new AnswerModels.SaveManifest(1, requestId, baseRef,
                new AnswerModels.Canvas(32.0, 32.0, -8.0, -8.0, "logical"),
                new AnswerModels.Document(1, layout, List.of(stroke)), List.of(composite));
    }

    static byte[] png(int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff152744);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        image.flush();
        return bytes.toByteArray();
    }

    private static AnswerModels.Block block(String name, int size, byte[] bytes) {
        return new AnswerModels.Block(0, name, 0.0, 0.0, (double) size, (double) size,
                size, size, AnswerManifestValidator.sha256(bytes));
    }
}
