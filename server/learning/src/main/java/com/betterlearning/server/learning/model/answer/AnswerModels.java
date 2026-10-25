package com.betterlearning.server.learning.model.answer;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** M2 v1 文件与作答契约，不包含历史版本或可执行内容。 */
public final class AnswerModels {
    private AnswerModels() {
    }

    public record Canvas(Double width, Double height, Double originX, Double originY,
            String coordinateUnit) {
    }
    public record Bounds(Double x, Double y, Double width, Double height) {
    }
    public record Point(Double x, Double y, @JsonInclude(JsonInclude.Include.NON_NULL) Double pressure) {
        public Point(Double x, Double y) {
            this(x, y, null);
        }
    }
    /** width 为所有笔种的最大笔宽；新增字段缺省时保持 M2 定宽渲染。 */
    public record Stroke(String strokeId, String color, Double width, List<Point> points,
            @JsonInclude(JsonInclude.Include.NON_NULL) String penKind) {
        public Stroke(String strokeId, String color, Double width, List<Point> points) {
            this(strokeId, color, width, points, null);
        }
    }
    public record LayoutBlock(String blockId, Double x, Double y, Double width, Double height) {
    }
    public record Block(Integer index, String partName, Double x, Double y, Double width,
            Double height, Integer pixelWidth, Integer pixelHeight, String sha256) {
    }
    public record QuestionLayout(String questionBodySha256, Double contentWidth, Double fontSize,
            String rendererId, Bounds bounds, List<LayoutBlock> blocks, List<Block> rasterBlocks) {
    }
    public record Document(Integer schemaVersion, QuestionLayout questionLayout, List<Stroke> strokes) {
    }
    public record SaveManifest(Integer schemaVersion, String saveRequestId, String baseFileRef,
            Canvas canvas, Document document, List<Block> blocks) {
    }
    public record QuestionSnapshot(String questionId, String subject, String bodyMarkdown) {
    }
    public record StoredManifest(SaveManifest source, QuestionSnapshot questionSnapshot) {
    }
    public record ReadBlock(Integer index, String partName, Double x, Double y, Double width,
            Double height, Integer pixelWidth, Integer pixelHeight, String sha256, String url) {
    }
    public record ReadLayout(String questionBodySha256, Double contentWidth, Double fontSize,
            String rendererId, Bounds bounds, List<LayoutBlock> blocks, List<ReadBlock> rasterBlocks) {
    }
    public record ReadDocument(Integer schemaVersion, ReadLayout questionLayout, List<Stroke> strokes) {
    }
    public record ReadManifest(Integer schemaVersion, String saveRequestId, String baseFileRef,
            Canvas canvas, ReadDocument document, List<ReadBlock> blocks, QuestionSnapshot questionSnapshot) {
    }
    public record SaveResult(String questionId, String state, String fileRef,
            OffsetDateTime savedAt, OffsetDateTime completedAt) {
    }
    public record Detail(String questionId, String state, String fileRef,
            OffsetDateTime savedAt, OffsetDateTime completedAt, ReadManifest manifest) {
    }
    public record StateRequest(String state, String fileRef) {
    }
}
