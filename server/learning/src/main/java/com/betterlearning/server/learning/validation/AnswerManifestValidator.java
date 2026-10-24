package com.betterlearning.server.learning.validation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.model.answer.AnswerModels;

/** 固定 v1 格式与完整整格校验；不重排题面，不裁切负世界内容。 */
public final class AnswerManifestValidator {
    public static final long MAX_MANIFEST_BYTES = 8L * 1024 * 1024;
    public static final long MAX_PNG_BYTES = 16L * 1024 * 1024;
    public static final long MAX_TOTAL_PNG_BYTES = 64L * 1024 * 1024;
    private static final long MAX_PIXELS = 64000000L;
    private static final int TILE = 1024;
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private AnswerManifestValidator() {
    }

    public static void validate(AnswerModels.SaveManifest manifest, String bodyMarkdown) {
        require(manifest != null && Integer.valueOf(1).equals(manifest.schemaVersion()));
        require(uuid(manifest.saveRequestId()));
        require(manifest.baseFileRef() == null || uuid(manifest.baseFileRef()));
        AnswerModels.Canvas canvas = manifest.canvas();
        require(canvas != null && "logical".equals(canvas.coordinateUnit()));
        int width = positiveInteger(canvas.width());
        int height = positiveInteger(canvas.height());
        coordinate(canvas.originX());
        coordinate(canvas.originY());
        require(canvas.originX() == Math.rint(canvas.originX()) && canvas.originY() == Math.rint(canvas.originY()));
        AnswerModels.Document document = manifest.document();
        require(document != null && Integer.valueOf(1).equals(document.schemaVersion()));
        AnswerModels.QuestionLayout layout = document.questionLayout();
        require(layout != null && sha256(bodyMarkdown.getBytes(StandardCharsets.UTF_8)).equals(layout.questionBodySha256()));
        require(finitePositive(layout.contentWidth()) && finitePositive(layout.fontSize()));
        require(layout.rendererId() != null && !layout.rendererId().isBlank() && layout.rendererId().length() <= 64);
        AnswerModels.Bounds bounds = layout.bounds();
        require(bounds != null);
        coordinate(bounds.x());
        coordinate(bounds.y());
        require(finitePositive(bounds.width()) && finitePositive(bounds.height()));
        inside(canvas, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        require(layout.blocks() != null && !layout.blocks().isEmpty());
        Set<String> ids = new HashSet<>();
        for (AnswerModels.LayoutBlock block : layout.blocks()) {
            require(block != null && textId(block.blockId()) && ids.add(block.blockId()));
            coordinate(block.x());
            coordinate(block.y());
            require(finitePositive(block.width()) && finitePositive(block.height()));
            require(block.x() >= bounds.x() && block.y() >= bounds.y()
                    && block.x() + block.width() <= bounds.x() + bounds.width()
                    && block.y() + block.height() <= bounds.y() + bounds.height());
        }
        require(document.strokes() != null && document.strokes().size() <= 20000);
        ids.clear();
        long points = 0;
        for (AnswerModels.Stroke stroke : document.strokes()) {
            require(stroke != null && textId(stroke.strokeId()) && ids.add(stroke.strokeId()));
            require(stroke.color() != null && stroke.color().matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"));
            require(stroke.width() != null && Double.isFinite(stroke.width())
                    && stroke.width() >= 0.5 && stroke.width() <= 64);
            require(stroke.points() != null && !stroke.points().isEmpty());
            points += stroke.points().size();
            require(points <= 200000);
            for (AnswerModels.Point point : stroke.points()) {
                require(point != null);
                coordinate(point.x());
                coordinate(point.y());
                double radius = stroke.width() / 2;
                inside(canvas, point.x() - radius, point.y() - radius, radius * 2, radius * 2);
            }
        }
        Set<String> names = new HashSet<>();
        long pixels = grid(manifest.blocks(), width, height, 0, 0, "chunk_", names);
        int rasterX = (int) Math.floor(bounds.x());
        int rasterY = (int) Math.floor(bounds.y());
        int rasterWidth = positiveInteger(Math.ceil(bounds.x() + bounds.width()) - rasterX);
        int rasterHeight = positiveInteger(Math.ceil(bounds.y() + bounds.height()) - rasterY);
        pixels += grid(layout.rasterBlocks(), rasterWidth, rasterHeight, rasterX, rasterY, "question_", names);
        if (pixels > MAX_PIXELS || names.size() > 256) {
            throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
        }
    }

    private static long grid(List<AnswerModels.Block> blocks, int width, int height,
            int originX, int originY, String prefix, Set<String> names) {
        long columns = ((long) width + TILE - 1) / TILE;
        long rows = ((long) height + TILE - 1) / TILE;
        if (columns * rows > 256) {
            throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
        }
        require(blocks != null && blocks.size() == columns * rows);
        long pixels = 0;
        for (int index = 0; index < blocks.size(); index++) {
            AnswerModels.Block block = blocks.get(index);
            int x = (int) (index % columns) * TILE;
            int y = (int) (index / columns) * TILE;
            int w = Math.min(TILE, width - x);
            int h = Math.min(TILE, height - y);
            require(block != null && Integer.valueOf(index).equals(block.index()));
            require((prefix + String.format(java.util.Locale.ROOT, "%04d", index)).equals(block.partName())
                    && names.add(block.partName()));
            require(Double.valueOf(originX + x).equals(block.x()) && Double.valueOf(originY + y).equals(block.y())
                    && Double.valueOf(w).equals(block.width()) && Double.valueOf(h).equals(block.height())
                    && Integer.valueOf(w).equals(block.pixelWidth()) && Integer.valueOf(h).equals(block.pixelHeight()));
            require(block.sha256() != null && block.sha256().matches("[0-9a-f]{64}"));
            pixels += (long) w * h;
        }
        return pixels;
    }

    public static boolean uuid(String value) {
        return value != null && value.matches(UUID);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private static boolean textId(String value) {
        return value != null && !value.isBlank() && value.length() <= 64;
    }

    private static void coordinate(Double value) {
        require(value != null && Double.isFinite(value) && Math.abs(value) <= 1000000);
    }

    private static boolean finitePositive(Double value) {
        return value != null && Double.isFinite(value) && value > 0 && value <= 2000000;
    }

    private static int positiveInteger(Double value) {
        require(finitePositive(value) && value == Math.rint(value));
        return value.intValue();
    }

    private static void inside(AnswerModels.Canvas canvas, double x, double y, double width, double height) {
        require(x >= canvas.originX() && y >= canvas.originY()
                && x + width <= canvas.originX() + canvas.width()
                && y + height <= canvas.originY() + canvas.height());
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }
}
