package com.betterlearning.server.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 两套独立模型配置；C1在app注册并映射环境变量，密钥无内置默认值。 */
@ConfigurationProperties("app.agent")
public class AgentProperties {
    private final Provider ocr = new Provider();
    private final Provider tutoring = new Provider();

    public Provider getOcr() {
        return ocr;
    }

    public Provider getTutoring() {
        return tutoring;
    }

    /** 不自动生成toString；密钥只在真实模型适配内部读取。 */
    public static class Provider {
        private String baseUrl;
        private String model;
        private String apiKey;
        private int timeoutSeconds = 120;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
