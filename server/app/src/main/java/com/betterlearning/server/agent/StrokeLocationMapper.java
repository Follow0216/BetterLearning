package com.betterlearning.server.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocationsStatus;
import com.betterlearning.server.multiagent.MultiAgentModels.WorldBounds;

import tools.jackson.databind.json.JsonMapper;

/** 从完整画布笔迹和合成块计算可证明覆盖的世界坐标定位。 */
final class StrokeLocationMapper {
    private static final int MAX_STROKES = 2000;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final int TILE = 1024;
    private static final int MAX_BLOCKS = 256;
    private static final JsonMapper JSON = new JsonMapper();

    private StrokeLocationMapper() {
    }

    record Mapping(StrokeLocationsStatus status, List<StrokeLocation> locations) {
    }

    static Mapping map(AnswerModels.SaveManifest manifest) {
        try {
            if (manifest == null || manifest.document() == null || manifest.document().strokes() == null) {
                return unavailable();
            }
            List<AnswerModels.Stroke> strokes = manifest.document().strokes();
            if (strokes.size() > MAX_STROKES) {
                return tooLarge();
            }
            Grid grid = grid(manifest.canvas(), manifest.blocks());
            if (grid == null) {
                return unavailable();
            }
            List<StrokeLocation> locations = new ArrayList<>(strokes.size());
            Set<String> strokeIds = new HashSet<>();
            for (AnswerModels.Stroke stroke : strokes) {
                Rect bounds = bounds(stroke);
                if (bounds == null || !strokeIds.add(stroke.strokeId())
                        || !inside(grid.canvas(), bounds)) {
                    return unavailable();
                }
                List<Integer> ordinals = new ArrayList<>();
                for (int index = 0; index < grid.blocks().size(); index++) {
                    Rect block = grid.blocks().get(index);
                    if (intersects(bounds, block)) {
                        ordinals.add(index);
                    }
                }
                if (ordinals.isEmpty()) {
                    return unavailable();
                }
                locations.add(new StrokeLocation(stroke.strokeId(),
                        new WorldBounds(bounds.left, bounds.top,
                        bounds.right - bounds.left, bounds.bottom - bounds.top),
                        List.copyOf(ordinals)));
            }
            if (JSON.writeValueAsBytes(locations).length > MAX_JSON_BYTES) {
                return tooLarge();
            }
            return new Mapping(StrokeLocationsStatus.COMPLETE, List.copyOf(locations));
        } catch (RuntimeException exception) {
            return unavailable();
        }
    }

    private static Grid grid(AnswerModels.Canvas canvas, List<AnswerModels.Block> values) {
        if (canvas == null || !"logical".equals(canvas.coordinateUnit())
                || !whole(canvas.width()) || !whole(canvas.height())
                || !finite(canvas.originX()) || !finite(canvas.originY())
                || canvas.originX() != Math.rint(canvas.originX())
                || canvas.originY() != Math.rint(canvas.originY())
                || values == null) {
            return null;
        }
        int width = canvas.width().intValue();
        int height = canvas.height().intValue();
        int columns = (width + TILE - 1) / TILE;
        int rows = (height + TILE - 1) / TILE;
        long expected = (long) columns * rows;
        if (expected > MAX_BLOCKS || values.size() != expected) {
            return null;
        }
        double canvasRight = canvas.originX() + width;
        double canvasBottom = canvas.originY() + height;
        if (!Double.isFinite(canvasRight) || !Double.isFinite(canvasBottom)
                || canvasRight <= canvas.originX() || canvasBottom <= canvas.originY()) {
            return null;
        }
        List<Rect> blocks = new ArrayList<>(values.size());
        for (int ordinal = 0; ordinal < values.size(); ordinal++) {
            AnswerModels.Block block = values.get(ordinal);
            int x = (ordinal % columns) * TILE;
            int y = (ordinal / columns) * TILE;
            int blockWidth = Math.min(TILE, width - x);
            int blockHeight = Math.min(TILE, height - y);
            if (block == null || block.index() == null || block.index() != ordinal
                    || block.x() == null || block.x() != x
                    || block.y() == null || block.y() != y
                    || block.width() == null || block.width() != blockWidth
                    || block.height() == null || block.height() != blockHeight
                    || block.pixelWidth() == null || block.pixelWidth() != blockWidth
                    || block.pixelHeight() == null || block.pixelHeight() != blockHeight) {
                return null;
            }
            double left = canvas.originX() + block.x();
            double top = canvas.originY() + block.y();
            double right = left + block.width();
            double bottom = top + block.height();
            if (!Double.isFinite(left) || !Double.isFinite(top)
                    || !Double.isFinite(right) || !Double.isFinite(bottom)
                    || right <= left || bottom <= top) {
                return null;
            }
            blocks.add(new Rect(left, top, right, bottom));
        }
        return new Grid(List.copyOf(blocks),
                new Rect(canvas.originX(), canvas.originY(), canvasRight, canvasBottom));
    }

    private static Rect bounds(AnswerModels.Stroke stroke) {
        if (stroke == null || stroke.strokeId() == null || stroke.strokeId().isBlank()
                || !positive(stroke.width()) || stroke.points() == null || stroke.points().isEmpty()) {
            return null;
        }
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (AnswerModels.Point point : stroke.points()) {
            if (point == null || !finite(point.x()) || !finite(point.y())) {
                return null;
            }
            left = Math.min(left, point.x());
            top = Math.min(top, point.y());
            right = Math.max(right, point.x());
            bottom = Math.max(bottom, point.y());
        }
        double radius = stroke.width() / 2;
        left -= radius;
        top -= radius;
        right += radius;
        bottom += radius;
        if (!Double.isFinite(left) || !Double.isFinite(top)
                || !Double.isFinite(right) || !Double.isFinite(bottom)
                || right <= left || bottom <= top) {
            return null;
        }
        return new Rect(left, top, right, bottom);
    }

    private static boolean inside(Rect canvas, Rect stroke) {
        return stroke.left >= canvas.left && stroke.top >= canvas.top
                && stroke.right <= canvas.right && stroke.bottom <= canvas.bottom;
    }

    private static boolean intersects(Rect left, Rect right) {
        return left.left < right.right && left.right > right.left
                && left.top < right.bottom && left.bottom > right.top;
    }

    private static boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static boolean positive(Double value) {
        return finite(value) && value > 0;
    }

    private static boolean whole(Double value) {
        return positive(value) && value <= 2_000_000 && value == Math.rint(value);
    }

    private static Mapping unavailable() {
        return new Mapping(StrokeLocationsStatus.UNAVAILABLE, List.of());
    }

    private static Mapping tooLarge() {
        return new Mapping(StrokeLocationsStatus.TOO_LARGE, List.of());
    }

    private record Rect(double left, double top, double right, double bottom) {
    }

    private record Grid(List<Rect> blocks, Rect canvas) {
    }
}
