package com.betterlearning.server.agent.runtime;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowStub;

/** Transport only: business authorization and outbox acknowledgement belong to the ledger. */
public final class TemporalRunGateway {
    private final WorkflowClient client;
    private final WorkflowRuntimeProperties properties;

    public TemporalRunGateway(WorkflowClient client, WorkflowRuntimeProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** A repeated outbox delivery must never reopen a completed business run. */
    public void start(long accountId, String runId) {
        requireAccountId(accountId);
        WorkflowStub workflow = client.newUntypedWorkflowStub(TemporalExecutionPolicy.WORKFLOW_TYPE,
                TemporalExecutionPolicy.workflow(runId, properties.getTaskQueue()));
        try {
            workflow.start(accountId, runId, properties.getModelTimeout().toMillis(),
                    properties.getStorageTimeout().toMillis());
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            // The same immutable business run was already accepted by Temporal.
        }
    }

    /** A missing workflow remains retryable until the ledger resolves start/cancel ordering. */
    public void requestCancellation(long accountId, String runId) {
        requireAccountId(accountId);
        client.newUntypedWorkflowStub(runId).signal("requestCancellation");
    }

    private static void requireAccountId(long accountId) {
        if (accountId <= 0) {
            throw new IllegalArgumentException("accountId must be positive");
        }
    }
}
