package com.betterlearning.server.learning.model.dto;

import jakarta.validation.constraints.NotBlank;

/** 创建题集，父级仅在创建时指定，null 表示根级。 */
public record CreateQuestionCollectionDTO(@NotBlank String name, String parentCollectionId) {
}
