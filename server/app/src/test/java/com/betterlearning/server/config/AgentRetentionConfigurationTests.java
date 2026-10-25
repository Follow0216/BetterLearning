package com.betterlearning.server.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.nio.file.Files;
import java.nio.file.Path;
import com.betterlearning.server.learning.config.AppFilesProperties;
import com.betterlearning.server.learning.mapper.RetentionMapper;
import com.betterlearning.server.learning.retention.DeletionJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

class AgentRetentionConfigurationTests {
    @TempDir
    Path directory;

    @Test
    void defaultsKeepPhysicalCleanupOffAndRequireIndependentExistingJournalInEveryMode() throws Exception {
        AgentRetentionProperties properties = new AgentRetentionProperties();
        assertThat(properties.isSweepEnabled()).isFalse();
        assertThat(properties.isInitializeJournal()).isFalse();
        assertThat(properties.getBatchSize()).isEqualTo(100);
        AppFilesProperties files = new AppFilesProperties();
        files.setRoot(Files.createDirectory(directory.resolve("files")).toString());
        AgentRetentionConfiguration configuration = new AgentRetentionConfiguration();
        RetentionMapper mapper = mock(RetentionMapper.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        assertThatThrownBy(() -> configuration.sourceDeletionService(properties, files, mapper, manager))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Every mode");
        properties.setDeleteLogRoot(directory.resolve("missing-journal").toString());
        assertThatThrownBy(() -> configuration.sourceDeletionService(properties, files, mapper, manager))
                .isInstanceOf(java.io.IOException.class);
        properties.setDeleteLogRoot(directory.resolve("files/embedded-journal").toString());
        properties.setInitializeJournal(true);
        assertThatThrownBy(() -> configuration.sourceDeletionService(properties, files, mapper, manager))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("independent");
        properties.setDeleteLogRoot(directory.resolve("independent-journal").toString());
        configuration.sourceDeletionService(properties, files, mapper, manager);
        properties.setInitializeJournal(false);
        assertThat(new DeletionJournal(Path.of(properties.getDeleteLogRoot())).readAll()).isEmpty();
        assertThat(configuration.sourceDeletionService(properties, files, mapper, manager)).isNotNull();
    }
}
