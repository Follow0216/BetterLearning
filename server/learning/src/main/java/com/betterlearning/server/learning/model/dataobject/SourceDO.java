package com.betterlearning.server.learning.model.dataobject;

import java.time.Instant;

/** 资料源行，所有读取由账号及 ACTIVE 状态限定。 */
public class SourceDO {
    private String sourceId;
    private Long accountId;
    private String type;
    private String originId;
    private String title;
    private String status;
    private Long currentVersion;
    private Instant createdAt;

    public String getSourceId() { return sourceId; }
    public void setSourceId(String value) { sourceId = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public String getType() { return type; }
    public void setType(String value) { type = value; }
    public String getOriginId() { return originId; }
    public void setOriginId(String value) { originId = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Long getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(Long value) { currentVersion = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
