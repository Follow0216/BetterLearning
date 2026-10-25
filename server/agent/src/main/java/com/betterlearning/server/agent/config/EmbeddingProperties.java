package com.betterlearning.server.agent.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 独立 embedding 接入配置；密钥没有默认值，也不参与异常或字符串表示。 */
@ConfigurationProperties("app.agent.embedding")
public class EmbeddingProperties {
    private boolean enabled;
    private String baseUrl;
    private String model = "qwen3.7-text-embedding";
    private String apiKey;
    private int dimensions = 1024;
    private int batchSize = 10;
    private int timeoutSeconds = 60;

    public void validate() {
        if (!enabled) {
            return;
        }
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()
                || model == null || model.isBlank()) {
            throw new IllegalStateException("Embedding 已启用，但接入地址、模型或密钥未配置");
        }
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalStateException("Embedding 接入地址无效");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Embedding 接入地址无效");
        }
        if (dimensions < 1 || dimensions > 4096 || batchSize < 1 || batchSize > 100
                || timeoutSeconds < 1 || timeoutSeconds > 600) {
            throw new IllegalStateException("Embedding 维度、批量大小或超时配置无效");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
