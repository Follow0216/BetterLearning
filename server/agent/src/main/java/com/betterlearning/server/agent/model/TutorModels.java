package com.betterlearning.server.agent.model;

import java.time.OffsetDateTime;
import java.util.List;

/** 跨端辅导的项目自有契约，不依赖learning或user持久化对象。 */
public final class TutorModels {
    private TutorModels() {
    }

    public enum Status {
        WAITING_ANSWER, GENERATING, SUCCEEDED, FAILED
    }

    public record CreateRequest(String requestId, String prompt) {
    }

    public record Turn(String turnId, String requestId, String questionId, String prompt,
            Status status, String fileRef, String answerMarkdown, String failureCode,
            String failureMessage, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record TurnList(List<Turn> items) {
        public TurnList {
            items = List.copyOf(items);
        }
    }

    public record Pending(Turn turn) {
    }

    public record GenerationClaim(Turn turn, boolean claimed) {
    }

    public record FrozenAnswer(String fileRef, String questionMarkdown, List<ModelImage> images) {
        public FrozenAnswer {
            images = List.copyOf(images);
        }
    }

    public record OcrResult(String bodyMarkdown) {
    }
}
