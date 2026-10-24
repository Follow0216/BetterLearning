package com.betterlearning.server.learning.model.vo;

import java.time.OffsetDateTime;

import com.betterlearning.server.learning.enums.QuestionSubject;

/** 列表项，不包含正文。 */
public record QuestionSummaryVO(String questionId, String collectionId, QuestionSubject subject,
        String summary, OffsetDateTime createdAt) {

    @Override
    public String toString() {
        return "QuestionSummaryVO{questionId=" + questionId + ", summary=[PROTECTED]}";
    }
}
