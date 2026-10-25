package com.betterlearning.server.learning;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;

import jakarta.servlet.http.Part;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;

import tools.jackson.databind.json.JsonMapper;

/** 大图预算测试共享数据：真实 1024 像素 PNG，私有 ancillary chunk 在 IEND 前且 CRC 正确。 */
public final class TutorBudgetTestData {
    public static final int MIB = 1024 * 1024;
    public static final String BODY = "求导 x²";
    private static final int TILE = 1024;
    private static final byte[] ANCILLARY_TYPE = "vpAg".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BASE_PNG = basePng();

    private TutorBudgetTestData() {
    }

    public record Fixture(AnswerModels.SaveManifest manifest, AnswerModels.QuestionSnapshot snapshot,
            List<Part> parts, List<byte[]> compositePngs, byte[] rasterPng) { }

    public static Fixture fixture(String questionId, String saveRequestId, List<Integer> compositeTargetBytes,
            int rasterTargetBytes, JsonMapper json) throws Exception {
        if (questionId == null || saveRequestId == null || compositeTargetBytes == null
                || compositeTargetBytes.isEmpty() || json == null) {
            throw new IllegalArgumentException("fixture inputs required");
        }
        int count = compositeTargetBytes.size();
        List<byte[]> composite = new ArrayList<>(count);
        List<AnswerModels.Block> blocks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            byte[] png = pngOfLength(compositeTargetBytes.get(index));
            composite.add(png);
            blocks.add(new AnswerModels.Block(index, String.format(java.util.Locale.ROOT, "chunk_%04d", index),
                    (double) (index * TILE), 0.0, (double) TILE, (double) TILE, TILE, TILE,
                    AnswerManifestValidator.sha256(png)));
        }
        byte[] raster = pngOfLength(rasterTargetBytes);
        AnswerModels.Block rasterBlock = new AnswerModels.Block(0, "question_0000", 0.0, 0.0,
                (double) TILE, (double) TILE, TILE, TILE, AnswerManifestValidator.sha256(raster));
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout(
                AnswerManifestValidator.sha256(BODY.getBytes(StandardCharsets.UTF_8)),
                (double) TILE, 16.0, "local-markdown-v1",
                new AnswerModels.Bounds(0.0, 0.0, (double) TILE, (double) TILE),
                List.of(new AnswerModels.LayoutBlock("body-0", 0.0, 0.0, (double) TILE, (double) TILE)),
                List.of(rasterBlock));
        AnswerModels.Stroke stroke = new AnswerModels.Stroke("stroke-1", "#152744", 2.0,
                List.of(new AnswerModels.Point(512.0, 512.0)));
        AnswerModels.SaveManifest manifest = new AnswerModels.SaveManifest(1, saveRequestId, null,
                new AnswerModels.Canvas((double) (count * TILE), (double) TILE, 0.0, 0.0, "logical"),
                new AnswerModels.Document(1, layout, List.of(stroke)), List.copyOf(blocks));
        AnswerModels.QuestionSnapshot snapshot = new AnswerModels.QuestionSnapshot(questionId, "OTHER", BODY);
        AnswerManifestValidator.validate(manifest, BODY);
        List<Part> parts = new ArrayList<>();
        parts.add(part("manifest", json.writeValueAsBytes(manifest), MediaType.APPLICATION_JSON));
        for (int index = 0; index < count; index++) {
            parts.add(part(blocks.get(index).partName(), composite.get(index), MediaType.IMAGE_PNG));
        }
        parts.add(part("question_0000", raster, MediaType.IMAGE_PNG));
        return new Fixture(manifest, snapshot, List.copyOf(parts), List.copyOf(composite), raster);
    }

    public static byte[] smallPng() {
        return BASE_PNG.clone();
    }

    public static byte[] pngOfLength(int targetBytes) {
        if (targetBytes == BASE_PNG.length) {
            return smallPng();
        }
        int payloadLength = targetBytes - BASE_PNG.length - 12;
        if (payloadLength < 0 || targetBytes > AnswerManifestValidator.MAX_PNG_BYTES) {
            throw new IllegalArgumentException("PNG target length out of range");
        }
        byte[] png = new byte[targetBytes];
        int insertion = BASE_PNG.length - 12;
        System.arraycopy(BASE_PNG, 0, png, 0, insertion);
        ByteBuffer output = ByteBuffer.wrap(png);
        output.position(insertion);
        output.putInt(payloadLength);
        output.put(ANCILLARY_TYPE);
        output.position(insertion + 8 + payloadLength);
        CRC32 crc = new CRC32();
        crc.update(ANCILLARY_TYPE);
        crc.update(png, insertion + 8, payloadLength);
        output.putInt((int) crc.getValue());
        System.arraycopy(BASE_PNG, insertion, png, insertion + 12 + payloadLength, 12);
        return png;
    }

    private static Part part(String name, byte[] bytes, MediaType type) {
        MockPart part = new MockPart(name, name, bytes);
        part.getHeaders().setContentType(type);
        return part;
    }

    private static byte[] basePng() {
        BufferedImage image = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff152744);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG encoder unavailable");
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length < 45 || !Arrays.equals(Arrays.copyOfRange(bytes, bytes.length - 8,
                    bytes.length - 4), "IEND".getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("unexpected PNG structure");
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } finally {
            image.flush();
        }
    }
}
