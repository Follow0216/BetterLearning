package com.betterlearning.server.config;

import com.betterlearning.server.agent.runtime.RunLifecycleActivities;
import com.betterlearning.server.agent.runtime.RunStepActivities;
import com.betterlearning.server.agent.runtime.LedgerRunLifecycle;
import com.betterlearning.server.agent.runtime.PipelineRunActivity;
import com.betterlearning.server.agent.runtime.ProviderCallDeadline;
import com.betterlearning.server.agent.runtime.TemporalWorkerHost;
import com.betterlearning.server.agent.runtime.WorkflowRuntimeProperties;
import com.betterlearning.server.multiagent.RolePipeline;
import com.betterlearning.server.multiagent.SnapshotPort;
import com.betterlearning.server.multiagent.StepExecutorFactory;
import com.betterlearning.server.multiagent.TutorLedger;
import io.temporal.client.WorkflowClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Enabling execution requires real ledger and pipeline Activity adapters; no fallback stub is installed. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.agent.workflow", name = "worker-enabled", havingValue = "true")
public class TemporalWorkerConfiguration {
    @Bean(destroyMethod = "close")
    ProviderCallDeadline providerCallDeadline(WorkflowRuntimeProperties properties) {
        return new ProviderCallDeadline(properties.getMaxConcurrentActivities());
    }

    @Bean
    RunLifecycleActivities runLifecycleActivities(TutorLedger ledger) {
        return new LedgerRunLifecycle(ledger, Clock.systemUTC());
    }

    @Bean
    RunStepActivities runStepActivities(TutorLedger ledger, SnapshotPort snapshots, RolePipeline pipeline,
            StepExecutorFactory executors, ProviderCallDeadline deadlines) {
        return new PipelineRunActivity(ledger, snapshots, pipeline, executors, deadlines);
    }

    @Bean
    TemporalWorkerHost temporalWorkerHost(WorkflowClient client, WorkflowRuntimeProperties properties,
            RunLifecycleActivities lifecycle, RunStepActivities steps) {
        return new TemporalWorkerHost(client, properties, lifecycle, steps);
    }
}
