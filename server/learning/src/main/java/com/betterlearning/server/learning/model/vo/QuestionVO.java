package com.betterlearning.server.learning.model.vo;

import java.time.OffsetDateTime;

import com.betterlearning.server.learning.enums.QuestionSubject;

/** 题目详情；ID 为字符串，正文仅在详情及确认结果返回。 */
public record QuestionVO(String questionId, String collectionId, QuestionSubject subject,
        String bodyMarkdown, String summary, OffsetDateTime createdAt) {

    @Override
    public String toString() {
        return "QuestionVO{questionId=" + questionId + ", content=[PROTECTED]}";
    }
}
