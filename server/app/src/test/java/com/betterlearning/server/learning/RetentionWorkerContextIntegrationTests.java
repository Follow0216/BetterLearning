package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;

import com.betterlearning.server.config.AgentRetentionConfiguration;
import com.betterlearning.server.config.AgentRetentionSweep;
import com.betterlearning.server.config.DataSourcePasswordProperties;
import com.betterlearning.server.config.JacksonConfiguration;
import com.betterlearning.server.config.MultiAgentRolloutConfiguration;
import com.betterlearning.server.config.MybatisConfiguration;
import com.betterlearning.server.learning.config.AppFilesProperties;
import com.betterlearning.server.learning.config.LearningMapperConfiguration;
import com.betterlearning.server.learning.retention.DeletionJournal;
import com.betterlearning.server.learning.retention.SourceDeletionService;
import com.betterlearning.server.learning.service.RolloutGate;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.service.impl.FileRetentionService;
import com.betterlearning.server.learning.service.impl.NoteServiceImpl;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.support.IntegrationContainers;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Covers P6 retention wiring in a small, non-HTTP worker-shaped context. */
@Testcontainers
@Tag("integration")
class RetentionWorkerContextIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @TempDir
    Path temporary;

    @Test
    void nonHttpContextWiresStoresDeletionReplayAndSweep() throws IOException {
        Path journalRoot = temporary.resolve("independent-journal");
        DeletionJournal.initialize(journalRoot);
        try (ConfigurableApplicationContext context = start(journalRoot)) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBean(SourceStore.class)).isNotNull();
            assertThat(context.getBean(AnswerFileStore.class)).isNotNull();
            assertThat(context.getBean(FileRetentionService.class)).isNotNull();
            assertThat(context.getBean(SourceDeletionService.class)).isNotNull();
            assertThat(context.getBean(AgentRetentionSweep.class)).isNotNull();
        }
    }

    @Test
    void nonHttpContextRejectsMissingJournalInsteadOfCreatingAnEmptyOne() {
        Path journalRoot = temporary.resolve("missing-journal");
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ignored = start(journalRoot)) {
                throw new AssertionError("Missing deletion journal was accepted");
            }
        }).hasRootCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("Deletion journal root is missing");
        assertThat(journalRoot).doesNotExist();
    }

    private ConfigurableApplicationContext start(Path journalRoot) {
        Path filesRoot = temporary.resolve("source-files");
        return new SpringApplicationBuilder(WorkerContext.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--app.files.root=" + filesRoot,
                        "--app.agent-retention.delete-log-root=" + journalRoot,
                        "--app.agent-retention.initialize-journal=false",
                        "--app.agent-retention.sweep-enabled=false",
                        "--app.multi-agent.rollout.mode=LEGACY",
                        "--spring.main.web-application-type=none");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties({DataSourcePasswordProperties.class, AppFilesProperties.class})
    @Import({MybatisConfiguration.class, JacksonConfiguration.class, AgentRetentionConfiguration.class,
            MultiAgentRolloutConfiguration.class, LearningMapperConfiguration.class, RolloutGate.class})
    @ComponentScan(basePackages = {
            "com.betterlearning.server.learning.service.impl",
            "com.betterlearning.server.learning.retention",
            "com.betterlearning.server.learning.dao",
            "com.betterlearning.server.learning.validation"},
            excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
            classes = NoteServiceImpl.class))
    static class WorkerContext {
    }
}
