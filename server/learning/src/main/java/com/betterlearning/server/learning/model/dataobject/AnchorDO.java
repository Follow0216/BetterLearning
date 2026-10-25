package com.betterlearning.server.learning.model.dataobject;

/** 引用定位行；由 SourceStore 转成不可变契约。 */
public class AnchorDO {
    private String anchorId;
    private String sourceId;
    private Long version;
    private String kind;
    private Integer ordinal;
    private String contentSha256;
    private String locatorJson;
    private String text;

    public String getAnchorId() { return anchorId; }
    public void setAnchorId(String value) { anchorId = value; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String value) { sourceId = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public String getKind() { return kind; }
    public void setKind(String value) { kind = value; }
    public Integer getOrdinal() { return ordinal; }
    public void setOrdinal(Integer value) { ordinal = value; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String value) { contentSha256 = value; }
    public String getLocatorJson() { return locatorJson; }
    public void setLocatorJson(String value) { locatorJson = value; }
    public String getText() { return text; }
    public void setText(String value) { text = value; }
}
