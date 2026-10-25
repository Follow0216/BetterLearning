package com.betterlearning.server.agent.runtime;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Internal Temporal protocol. Implementations adapt the P1 ledger and P3 pipeline ports. */
@ActivityInterface
public interface RunLifecycleActivities {
    /** Claim the current run, or return zero for a terminal/stale/cancelled run. */
    @ActivityMethod(name = "TutorBeginRunV1")
    long begin(long accountId, String runId);

    /** Idempotently resolve an ordinal to a step ID; null means the ledger reached a terminal state. */
    @ActivityMethod(name = "TutorNextStepV1")
    String nextStep(long accountId, String runId, int ordinal);

    /** Inspect persisted output after an Activity failure; never issue another provider call. */
    @ActivityMethod(name = "TutorRecoverStepV1")
    boolean recoverStep(long accountId, String runId, String stepId);

    /** CAS settlement. A committed terminal state or cancellation always wins over a late caller. */
    @ActivityMethod(name = "TutorSettleRunV1")
    void settle(long accountId, String runId, String reason);
}
