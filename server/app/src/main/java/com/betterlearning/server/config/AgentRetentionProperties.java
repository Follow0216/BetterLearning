package com.betterlearning.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Deletion SLA and minimum historical retention are fixed, not weakening switches. */
@Validated
@ConfigurationProperties("app.agent-retention")
public class AgentRetentionProperties {
    private String deleteLogRoot = "";
    private boolean initializeJournal;
    private boolean sweepEnabled;
    @Min(1)
    @Max(1000)
    private int batchSize = 100;

    public String getDeleteLogRoot() { return deleteLogRoot; }
    public void setDeleteLogRoot(String value) { deleteLogRoot = value; }
    public boolean isInitializeJournal() { return initializeJournal; }
    public void setInitializeJournal(boolean value) { initializeJournal = value; }
    public boolean isSweepEnabled() { return sweepEnabled; }
    public void setSweepEnabled(boolean value) { sweepEnabled = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
}
