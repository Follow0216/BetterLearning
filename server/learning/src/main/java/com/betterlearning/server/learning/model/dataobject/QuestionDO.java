package com.betterlearning.server.learning.model.dataobject;

import java.time.OffsetDateTime;

/** 题目持久化行，不向客户端暴露 owner 或 requestId。 */
public class QuestionDO {

    private Long questionId;

    private Long accountId;

    private Long collectionId;

    private String requestId;

    private String subject;

    private String bodyMarkdown;

    private String summary;

    private String answerState;

    private OffsetDateTime createdAt;

    /** 返回 questionId。 */
    public Long getQuestionId() {
        return questionId;
    }

    /** 设置 questionId。 */
    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    /** 返回 accountId。 */
    public Long getAccountId() {
        return accountId;
    }

    /** 设置 accountId。 */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    /** 返回 collectionId。 */
    public Long getCollectionId() {
        return collectionId;
    }

    /** 设置 collectionId。 */
    public void setCollectionId(Long collectionId) {
        this.collectionId = collectionId;
    }

    /** 返回 requestId。 */
    public String getRequestId() {
        return requestId;
    }

    /** 设置 requestId。 */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /** 返回 subject。 */
    public String getSubject() {
        return subject;
    }

    /** 设置 subject。 */
    public void setSubject(String subject) {
        this.subject = subject;
    }

    /** 返回 bodyMarkdown。 */
    public String getBodyMarkdown() {
        return bodyMarkdown;
    }

    /** 设置 bodyMarkdown。 */
    public void setBodyMarkdown(String bodyMarkdown) {
        this.bodyMarkdown = bodyMarkdown;
    }

    /** 返回 summary。 */
    public String getSummary() {
        return summary;
    }

    /** 设置 summary。 */
    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getAnswerState() {
        return answerState;
    }

    public void setAnswerState(String answerState) {
        this.answerState = answerState;
    }

    /** 返回 createdAt。 */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** 设置 createdAt。 */
    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
