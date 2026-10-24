package com.betterlearning.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.betterlearning.server.agent.config.AgentModelConfiguration;
import com.betterlearning.server.agent.config.AgentProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentPropertiesMappingTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AgentModelConfiguration.class);

    @Test
    void mapsIndependentProvidersAndPreservesBaseUrlPrefix() {
        runner.withPropertyValues("APP_AGENT_OCR_BASE_URL=https://ocr.example.invalid/custom/v1",
                "APP_AGENT_OCR_MODEL=ocr-unit-model", "APP_AGENT_OCR_API_KEY=unit-dummy-ocr",
                "APP_AGENT_OCR_TIMEOUT_SECONDS=45", "APP_AGENT_TUTORING_BASE_URL=https://tutor.example.invalid/prefix/v1",
                "APP_AGENT_TUTORING_MODEL=tutor-unit-model", "APP_AGENT_TUTORING_API_KEY=unit-dummy-tutor",
                "APP_AGENT_TUTORING_TIMEOUT_SECONDS=90").run(context -> {
                    assertThat(context).hasNotFailed();
                    AgentProperties properties = context.getBean(AgentProperties.class);
                    assertThat(properties.getOcr().getBaseUrl()).isEqualTo("https://ocr.example.invalid/custom/v1");
                    assertThat(properties.getOcr().getModel()).isEqualTo("ocr-unit-model");
                    assertThat(properties.getOcr().getApiKey()).isEqualTo("unit-dummy-ocr");
                    assertThat(properties.getOcr().getTimeoutSeconds()).isEqualTo(45);
                    assertThat(properties.getTutoring().getBaseUrl()).isEqualTo("https://tutor.example.invalid/prefix/v1");
                    assertThat(properties.getTutoring().getModel()).isEqualTo("tutor-unit-model");
                    assertThat(properties.getTutoring().getApiKey()).isEqualTo("unit-dummy-tutor");
                    assertThat(properties.getTutoring().getTimeoutSeconds()).isEqualTo(90);
                });
    }

    @Test
    void absentAiConfigurationDoesNotFailBindingOrSetRealDefaults() {
        runner.withPropertyValues("APP_AGENT_OCR_BASE_URL=", "APP_AGENT_OCR_MODEL=", "APP_AGENT_OCR_API_KEY=",
                "APP_AGENT_TUTORING_BASE_URL=", "APP_AGENT_TUTORING_MODEL=", "APP_AGENT_TUTORING_API_KEY=",
                "APP_AGENT_OCR_TIMEOUT_SECONDS=120", "APP_AGENT_TUTORING_TIMEOUT_SECONDS=120").run(context -> {
                    assertThat(context).hasNotFailed();
                    AgentProperties properties = context.getBean(AgentProperties.class);
                    assertThat(properties.getOcr().getBaseUrl()).isNullOrEmpty();
                    assertThat(properties.getOcr().getModel()).isNullOrEmpty();
                    assertThat(properties.getOcr().getApiKey()).isNullOrEmpty();
                    assertThat(properties.getTutoring().getBaseUrl()).isNullOrEmpty();
                    assertThat(properties.getTutoring().getModel()).isNullOrEmpty();
                    assertThat(properties.getTutoring().getApiKey()).isNullOrEmpty();
                    assertThat(properties.getOcr().getTimeoutSeconds()).isEqualTo(120);
                    assertThat(properties.getTutoring().getTimeoutSeconds()).isEqualTo(120);
                });
    }
}
