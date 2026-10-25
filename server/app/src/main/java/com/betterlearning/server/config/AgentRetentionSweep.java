package com.betterlearning.server.config;

import com.betterlearning.server.learning.retention.SourceRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** No source IDs, titles, text, paths, or provider output in operational logs. */
public class AgentRetentionSweep {
    private static final Logger LOG = LoggerFactory.getLogger(AgentRetentionSweep.class);
    private final AgentRetentionProperties properties;
    private final SourceRetentionService retention;
    private final com.betterlearning.server.learning.service.impl.FileRetentionService files;

    public AgentRetentionSweep(AgentRetentionProperties properties, SourceRetentionService retention,
            com.betterlearning.server.learning.service.impl.FileRetentionService files) {
        this.files = files;
        this.properties = properties;
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "${app.agent-retention.sweep-delay-ms:60000}",
            fixedDelayString = "${app.agent-retention.sweep-delay-ms:60000}")
    public void sweep() {
        try {
            boolean enabled = properties.isSweepEnabled() && !properties.getDeleteLogRoot().isBlank();
            var evidence = enabled ? retention.sweep(properties.getBatchSize()) : retention.evidence();
            LOG.info("retention cleanupEnabled={} pending={} overdue={} activeRunHolds={} purgedVersions={} retiredCandidates={} unclearedFiles={} overdueFiles={} unjournaledDeletions={}",
                    enabled, evidence.pendingDeletions(), evidence.overdueDeletions(), evidence.activeRunHolds(),
                    evidence.purgedVersions(), evidence.retiredCandidates(), evidence.unclearedFiles(), evidence.overdueFiles(), evidence.unjournaledDeletions());
            var audit = files.knownFileAudit(properties.getBatchSize());
            LOG.info("retention fileStates={} sampledFiles={} missingObjects={}", files.groupedCounts(), audit.checked(), audit.missing());
        } catch (RuntimeException failure) {
            LOG.error("retention sweep failed; pending data retained for reconciliation");
        }
    }
}
