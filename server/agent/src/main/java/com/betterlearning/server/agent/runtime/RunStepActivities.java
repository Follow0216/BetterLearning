package com.betterlearning.server.agent.runtime;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** A step persists intent and reserves budget before external execution. */
@ActivityInterface
public interface RunStepActivities {
    @ActivityMethod(name = "TutorExecuteStepV1")
    void executeStep(long accountId, String runId, String stepId, long modelTimeoutMillis);
}
