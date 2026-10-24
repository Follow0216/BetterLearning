package com.betterlearning.server.learning.model.dto;

import jakarta.validation.constraints.NotBlank;

/** 单题移动，inbox 表示待整理。 */
public record MoveQuestionDTO(@NotBlank String collectionId) {
}
