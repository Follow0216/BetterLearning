package com.betterlearning.server.learning.model.dto;

import jakarta.validation.constraints.NotBlank;

/** 仅改名，不接受父级或 owner。 */
public record RenameQuestionCollectionDTO(@NotBlank String name) {
}
