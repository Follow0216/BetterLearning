package com.betterlearning.server.multiagent;

import com.betterlearning.server.multiagent.MultiAgentModels.*;

/** v1 存储定位器校验；JSON解析必须拒绝未知字段，文件坐标还需与已授权manifest比对。 */
public final class AnchorLocators {
    private AnchorLocators() { }

    public static void validate(String kind, String text, AnchorLocator locator) {
        if (locator == null || locator.schemaVersion() != 1) {
            throw new IllegalArgumentException("unsupported locator schema");
        }
        boolean markdown = locator.markdownLocator() != null;
        boolean handwriting = locator.handwritingLocator() != null;
        if (markdown == handwriting || ("MARKDOWN_BLOCK".equals(kind) && !markdown)
                || ("HANDWRITING_STEP".equals(kind) && !handwriting)
                || !("MARKDOWN_BLOCK".equals(kind) || "HANDWRITING_STEP".equals(kind) || "QUESTION_BLOCK".equals(kind))) {
            throw new IllegalArgumentException("locator kind mismatch");
        }
        if (markdown) {
            MarkdownLocator value = locator.markdownLocator();
            if (text == null || value.blockOrdinal() < 0 || value.startOffset() < 0
                    || value.endOffset() <= value.startOffset() || value.endOffset() > text.length()
                    || splitsPair(text, value.startOffset()) || splitsPair(text, value.endOffset())) {
                throw new IllegalArgumentException("invalid UTF-16 range");
            }
        } else {
            var segments = locator.handwritingLocator().segments();
            if (segments == null || segments.isEmpty() || segments.size() > 256) {
                throw new IllegalArgumentException("invalid segment count");
            }
            for (HandwritingSegment segment : segments) {
                if (segment == null || segment.blockIndex() < 0 || segment.pixelWidth() < 1
                        || segment.pixelHeight() < 1 || !bounds(segment.blockWorldBounds())
                        || !bounds(segment.regionWorldBounds()) || !contains(segment.blockWorldBounds(), segment.regionWorldBounds())) {
                    throw new IllegalArgumentException("invalid handwriting segment");
                }
            }
        }
    }

    private static boolean splitsPair(String value, int offset) {
        return offset > 0 && offset < value.length() && Character.isHighSurrogate(value.charAt(offset - 1))
                && Character.isLowSurrogate(value.charAt(offset));
    }

    private static boolean bounds(WorldBounds bounds) {
        return bounds != null && Double.isFinite(bounds.x()) && Double.isFinite(bounds.y())
                && Double.isFinite(bounds.width()) && Double.isFinite(bounds.height())
                && bounds.width() > 0 && bounds.height() > 0
                && Double.isFinite(bounds.x() + bounds.width()) && Double.isFinite(bounds.y() + bounds.height());
    }

    private static boolean contains(WorldBounds outer, WorldBounds inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
                && inner.x() + inner.width() <= outer.x() + outer.width()
                && inner.y() + inner.height() <= outer.y() + outer.height();
    }
}
