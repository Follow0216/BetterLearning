package com.betterlearning.server.learning.model.dataobject;

import java.time.Instant;

/** 不可变资料版本行。 */
public class SourceVersionDO {
    private String sourceId;
    private Long version;
    private String contentSha256;
    private String status;
    private String answerState;
    private String fileRef;
    private String bodyMarkdown;
    private Instant createdAt;
    private Long indexRevision;

    public String getSourceId() { return sourceId; }
    public void setSourceId(String value) { sourceId = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String value) { contentSha256 = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getAnswerState() { return answerState; }
    public void setAnswerState(String value) { answerState = value; }
    public String getFileRef() { return fileRef; }
    public void setFileRef(String value) { fileRef = value; }
    public String getBodyMarkdown() { return bodyMarkdown; }
    public void setBodyMarkdown(String value) { bodyMarkdown = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Long getIndexRevision() { return indexRevision; }
    public void setIndexRevision(Long value) { indexRevision = value; }
}
