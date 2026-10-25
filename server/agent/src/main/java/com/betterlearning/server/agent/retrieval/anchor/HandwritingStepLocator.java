package com.betterlearning.server.agent.retrieval.anchor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 从原始块世界坐标构造完整覆盖步骤的引用定位器。 */
public final class HandwritingStepLocator {
    private HandwritingStepLocator() {
    }

    public record Bounds(double x, double y, double width, double height) {
        public Bounds {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width)
                    || !Double.isFinite(height) || width <= 0 || height <= 0
                    || !Double.isFinite(x + width) || !Double.isFinite(y + height)) {
                throw new IllegalArgumentException("invalid world bounds");
            }
        }

        double right() {
            return x + width;
        }

        double bottom() {
            return y + height;
        }
    }

    /** 原始 manifest 的块几何与服务端生成的本地资产路径。生产调用方必须先完成授权，
     * 从 citation/segment 重建受保护路径；不得将客户端 URL 原样传入。URL 本身不授予访问权。 */
    public record Block(int blockIndex, String assetUrl, Bounds blockWorldBounds,
            int pixelWidth, int pixelHeight) {
        public Block {
            if (blockIndex < 0 || !localPath(assetUrl)
                    || blockWorldBounds == null || pixelWidth <= 0 || pixelHeight <= 0) {
                throw new IllegalArgumentException("invalid source block");
            }
        }
    }

    public record Segment(int blockIndex, String assetUrl, Bounds blockWorldBounds,
            int pixelWidth, int pixelHeight, Bounds regionWorldBounds, List<String> strokeIds) {
        public Segment {
            if (blockIndex < 0 || !localPath(assetUrl)
                    || blockWorldBounds == null || regionWorldBounds == null
                    || pixelWidth <= 0 || pixelHeight <= 0
                    || !contains(blockWorldBounds, regionWorldBounds)) {
                throw new IllegalArgumentException("invalid segment");
            }
            if (strokeIds != null) {
                Set<String> unique = new HashSet<>();
                for (String id : strokeIds) {
                    if (id == null || id.isBlank() || !unique.add(id)) {
                        throw new IllegalArgumentException("invalid strokeIds");
                    }
                }
                strokeIds = List.copyOf(strokeIds);
            }
        }
    }

    public record Locator(List<Segment> segments) {
        public Locator {
            if (segments == null || segments.isEmpty()) {
                throw new IllegalArgumentException("segments are required");
            }
            segments = List.copyOf(segments);
        }
    }

    /** 覆盖不完整时拒绝构造可点击引用；坐标/尺寸直接来自块。 */
    public static Locator locate(Bounds step, List<Block> blocks, List<String> strokeIds) {
        if (step == null || blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("step and blocks are required");
        }
        Set<Integer> indexes = new HashSet<>();
        List<Segment> segments = new ArrayList<>();
        for (Block block : blocks) {
            if (block == null || !indexes.add(block.blockIndex())) {
                throw new IllegalArgumentException("duplicate or null block");
            }
            Bounds region = intersection(step, block.blockWorldBounds());
            if (region != null) {
                segments.add(new Segment(block.blockIndex(), block.assetUrl(),
                        block.blockWorldBounds(), block.pixelWidth(), block.pixelHeight(), region, strokeIds));
            }
        }
        if (!covers(step, segments)) {
            throw new IllegalArgumentException("blocks do not cover the whole step");
        }
        return new Locator(segments);
    }

    private static Bounds intersection(Bounds a, Bounds b) {
        double left = Math.max(a.x(), b.x());
        double top = Math.max(a.y(), b.y());
        double right = Math.min(a.right(), b.right());
        double bottom = Math.min(a.bottom(), b.bottom());
        return right > left && bottom > top ? new Bounds(left, top, right - left, bottom - top) : null;
    }

    private static boolean contains(Bounds outer, Bounds inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
                && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
    }

    private static boolean localPath(String assetUrl) {
        if (assetUrl == null || !assetUrl.startsWith("/") || assetUrl.startsWith("//")
                || assetUrl.indexOf('\\') >= 0 || assetUrl.indexOf('?') >= 0
                || assetUrl.indexOf('#') >= 0 || assetUrl.contains("/../")
                || assetUrl.endsWith("/..") || assetUrl.contains("/./")
                || assetUrl.endsWith("/.")) {
            return false;
        }
        for (int index = 0; index < assetUrl.length(); index++) {
            if (Character.isISOControl(assetUrl.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean covers(Bounds target, List<Segment> segments) {
        List<Double> edges = new ArrayList<>();
        edges.add(target.x());
        edges.add(target.right());
        for (Segment segment : segments) {
            edges.add(segment.regionWorldBounds().x());
            edges.add(segment.regionWorldBounds().right());
        }
        edges.sort(Double::compare);
        for (int i = 1; i < edges.size(); i++) {
            double left = edges.get(i - 1);
            double right = edges.get(i);
            if (left == right) {
                continue;
            }
            List<Bounds> spanning = new ArrayList<>();
            for (Segment segment : segments) {
                Bounds region = segment.regionWorldBounds();
                if (region.x() <= left && region.right() >= right) {
                    spanning.add(region);
                }
            }
            spanning.sort((a, b) -> Double.compare(a.y(), b.y()));
            double coveredTo = target.y();
            for (Bounds region : spanning) {
                if (region.y() > coveredTo) {
                    return false;
                }
                coveredTo = Math.max(coveredTo, region.bottom());
            }
            if (coveredTo < target.bottom()) {
                return false;
            }
        }
        return !segments.isEmpty();
    }
}
