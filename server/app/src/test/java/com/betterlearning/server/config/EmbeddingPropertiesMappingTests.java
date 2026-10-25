package com.betterlearning.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.betterlearning.server.agent.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class EmbeddingPropertiesMappingTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(EmbeddingConfiguration.class);

    @Test
    void defaultsToDisabledWithIndependentValues() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            EmbeddingProperties properties = context.getBean(EmbeddingProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getModel()).isEqualTo("qwen3.7-text-embedding");
            assertThat(properties.getDimensions()).isEqualTo(1024);
            assertThat(properties.getBatchSize()).isEqualTo(10);
            assertThat(properties.getTimeoutSeconds()).isEqualTo(60);
        });
    }

    @Test
    void mapsAllEnvironmentValues() {
        runner.withPropertyValues("APP_AGENT_EMBEDDING_ENABLED=true",
                "APP_AGENT_EMBEDDING_BASE_URL=https://embed.example.invalid/v1",
                "APP_AGENT_EMBEDDING_MODEL=unit-model", "APP_AGENT_EMBEDDING_API_KEY=unit-secret",
                "APP_AGENT_EMBEDDING_DIMENSIONS=2", "APP_AGENT_EMBEDDING_BATCH_SIZE=3",
                "APP_AGENT_EMBEDDING_TIMEOUT_SECONDS=7").run(context -> {
                    assertThat(context).hasNotFailed();
                    EmbeddingProperties properties = context.getBean(EmbeddingProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getBaseUrl()).isEqualTo("https://embed.example.invalid/v1");
                    assertThat(properties.getModel()).isEqualTo("unit-model");
                    assertThat(properties.getApiKey()).isEqualTo("unit-secret");
                    assertThat(properties.getDimensions()).isEqualTo(2);
                    assertThat(properties.getBatchSize()).isEqualTo(3);
                    assertThat(properties.getTimeoutSeconds()).isEqualTo(7);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EmbeddingProperties.class)
    static class EmbeddingConfiguration {
    }
}
