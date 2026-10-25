package com.betterlearning.server.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 两个独立门禁；默认保留旧转写与召回，候选不代表数学正确性。 */
@ConfigurationProperties("app.agent.handwriting-methods")
public class HandwritingMethodsProperties {
    private boolean enabled;
    private boolean recallEnabled;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isRecallEnabled() { return recallEnabled; }
    public void setRecallEnabled(boolean recallEnabled) { this.recallEnabled = recallEnabled; }
}
