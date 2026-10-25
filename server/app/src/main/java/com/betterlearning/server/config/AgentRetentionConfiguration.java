package com.betterlearning.server.config;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import com.betterlearning.server.learning.config.AppFilesProperties;
import com.betterlearning.server.learning.mapper.RetentionMapper;
import com.betterlearning.server.learning.retention.DeletionJournal;
import com.betterlearning.server.learning.retention.SourceDeletionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentRetentionProperties.class)
@EnableScheduling
public class AgentRetentionConfiguration {
    @Bean
    AgentRetentionSweep agentRetentionSweep(AgentRetentionProperties properties,
            com.betterlearning.server.learning.retention.SourceRetentionService retention,
            com.betterlearning.server.learning.service.impl.FileRetentionService files) {
        return new AgentRetentionSweep(properties, retention, files);
    }

    @Bean(initMethod = "recoverBeforeServing")
    @DependsOnDatabaseInitialization
    @DependsOn("answerFileStore")
    SourceDeletionService sourceDeletionService(AgentRetentionProperties properties, AppFilesProperties files,
            RetentionMapper mapper, PlatformTransactionManager manager) throws IOException {
        DeletionJournal journal = null;
        if (!properties.getDeleteLogRoot().isBlank()) {
            Path root = Path.of(properties.getDeleteLogRoot()).normalize();
            Path objects = Path.of(files.getRoot()).normalize();
            if (!root.isAbsolute() || root.startsWith(objects) || objects.startsWith(root)) {
                throw new IllegalStateException("Deletion journal must be independent of APP_FILES_ROOT");
            }
            journal = properties.isInitializeJournal() ? DeletionJournal.initialize(root) : new DeletionJournal(root);
            if (Files.exists(objects)) {
                Path realRoot = root.toRealPath();
                Path realObjects = objects.toRealPath();
                if (realRoot.startsWith(realObjects) || realObjects.startsWith(realRoot)) {
                    throw new IllegalStateException("Deletion journal must use an independent directory");
                }
            }
        } else {
            throw new IllegalStateException("Every mode requires APP_AGENT_RETENTION_DELETE_LOG_ROOT for safe restore");
        }
        return new SourceDeletionService(journal, mapper, new TransactionTemplate(manager));
    }
}
