package com.betterlearning.server.learning.model.dataobject;

import java.time.OffsetDateTime;

/** 每题当前作答指针，不保留历史。 */
public class QuestionAnswerDO {

    private Long accountId;

    private Long questionId;

    private String fileRef;

    private String saveRequestId;

    private String requestHash;

    private String state;

    private OffsetDateTime savedAt;

    private OffsetDateTime completedAt;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public String getFileRef() {
        return fileRef;
    }

    public void setFileRef(String fileRef) {
        this.fileRef = fileRef;
    }

    public String getSaveRequestId() {
        return saveRequestId;
    }

    public void setSaveRequestId(String saveRequestId) {
        this.saveRequestId = saveRequestId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public OffsetDateTime getSavedAt() {
        return savedAt;
    }

    public void setSavedAt(OffsetDateTime savedAt) {
        this.savedAt = savedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
