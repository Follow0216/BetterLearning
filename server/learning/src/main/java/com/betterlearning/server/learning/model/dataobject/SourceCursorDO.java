package com.betterlearning.server.learning.model.dataobject;

import java.time.Instant;

/** 服务器保存的账号/类型绑定分页位置。 */
public class SourceCursorDO {
    private Long accountId;
    private String type;
    private Instant beforeTime;
    private String beforeId;

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public String getType() { return type; }
    public void setType(String value) { type = value; }
    public Instant getBeforeTime() { return beforeTime; }
    public void setBeforeTime(Instant value) { beforeTime = value; }
    public String getBeforeId() { return beforeId; }
    public void setBeforeId(String value) { beforeId = value; }
}
