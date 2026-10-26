package com.betterlearning.server.agent.model;

import java.util.List;
import java.util.Objects;

import io.swagger.v3.oas.annotations.media.Schema;

/** 批量识别的有界内部数据；原图字节不出现在响应或默认toString中。 */
public final class BatchOcrModels {
    private BatchOcrModels() {
    }

    public record InputImage(String sourceId, int order, String mediaType, byte[] bytes) {
        public InputImage {
            bytes = Objects.requireNonNull(bytes, "缺少图像内容").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        public int byteCount() {
            return bytes.length;
        }

        @Override
        public String toString() {
            return "InputImage[sourceId=" + sourceId + ", order=" + order + ", byteCount=" + bytes.length + "]";
        }
    }

    public record Source(String sourceId, int order, int width, int height) {
    }

    public record Tile(String tileId, String sourceId, int x, int y, int width, int height,
            boolean repair, String markdown) {
    }

    public record Question(String itemId, String bodyMarkdown, List<String> sourceIds, List<String> tileIds,
            boolean needsReview, List<String> warnings) {
        public Question {
            sourceIds = List.copyOf(sourceIds);
            tileIds = List.copyOf(tileIds);
            warnings = List.copyOf(warnings);
        }
    }

    @Schema(name = "BatchOcrResult")
    public record Result(List<Question> questions, List<Source> sources, List<Tile> tiles,
            List<String> warnings, boolean incomplete) {
        public Result {
            questions = List.copyOf(questions);
            sources = List.copyOf(sources);
            tiles = List.copyOf(tiles);
            warnings = List.copyOf(warnings);
        }
    }
}
