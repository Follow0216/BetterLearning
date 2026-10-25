package com.betterlearning.server.agent.runtime;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** History stores identifiers and deployment timeouts, never source text or model output. */
@WorkflowInterface
public interface TutorRunWorkflow {
    @WorkflowMethod(name = TemporalExecutionPolicy.WORKFLOW_TYPE)
    void execute(long accountId, String runId, long modelTimeoutMillis, long storageTimeoutMillis);

    @SignalMethod
    void requestCancellation();
}
