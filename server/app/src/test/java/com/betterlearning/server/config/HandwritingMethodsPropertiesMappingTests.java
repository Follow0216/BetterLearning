package com.betterlearning.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.betterlearning.server.agent.config.HandwritingMethodsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class HandwritingMethodsPropertiesMappingTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(MethodsConfiguration.class);

    @Test
    void defaultsKeepBothExtractionAndRecallDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            HandwritingMethodsProperties properties = context.getBean(HandwritingMethodsProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.isRecallEnabled()).isFalse();
        });
    }

    @Test
    void extractionCanStoreCandidatesWithoutChangingRecall() {
        runner.withPropertyValues("APP_AGENT_HANDWRITING_METHODS_ENABLED=true").run(context -> {
            assertThat(context).hasNotFailed();
            HandwritingMethodsProperties properties = context.getBean(HandwritingMethodsProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.isRecallEnabled()).isFalse();
        });
    }

    @Test
    void recallAndNewExtractionHaveIndependentRollbackSwitches() {
        runner.withPropertyValues("APP_AGENT_HANDWRITING_METHODS_ENABLED=false",
                "APP_AGENT_HANDWRITING_METHODS_RECALL_ENABLED=true").run(context -> {
                    assertThat(context).hasNotFailed();
                    HandwritingMethodsProperties properties = context.getBean(HandwritingMethodsProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.isRecallEnabled()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HandwritingMethodsProperties.class)
    static class MethodsConfiguration {
    }
}
