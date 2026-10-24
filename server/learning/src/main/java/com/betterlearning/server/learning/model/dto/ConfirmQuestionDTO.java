package com.betterlearning.server.learning.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 一次确认动作；重试须保留 requestId 和完整请求体，不接受客户端指定账号或题集。
 *
 * @param requestId 小写标准 UUID 字符串
 * @param subject 稳定学科代码
 * @param bodyMarkdown 确认后的 Markdown 原文
 */
public record ConfirmQuestionDTO(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        String requestId,
        @Schema(allowableValues = {"HIGHER_MATHEMATICS", "OTHER"})
        @NotBlank @Pattern(regexp = "HIGHER_MATHEMATICS|OTHER") String subject,
        @NotBlank @Size(max = 20000) String bodyMarkdown) {

    @Override
    public String toString() {
        return "ConfirmQuestionDTO{content=[PROTECTED]}";
    }
}
