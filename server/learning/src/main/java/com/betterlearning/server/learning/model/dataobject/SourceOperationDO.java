package com.betterlearning.server.learning.model.dataobject;

/** 账号内明确操作的幂等结果。 */
public class SourceOperationDO {
    private String payloadFingerprint;
    private String sourceId;
    private Long version;

    public String getPayloadFingerprint() { return payloadFingerprint; }
    public void setPayloadFingerprint(String value) { payloadFingerprint = value; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String value) { sourceId = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
}
