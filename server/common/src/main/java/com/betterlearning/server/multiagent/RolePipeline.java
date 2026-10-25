package com.betterlearning.server.multiagent;

import com.betterlearning.server.multiagent.MultiAgentModels.PipelineContext;
import com.betterlearning.server.multiagent.MultiAgentModels.PipelineResult;

/** 四角色有限流水线，由运行时在 Activity 内调用；收费调用均经过 TutorLedger。 */
public interface RolePipeline {
    PipelineResult execute(PipelineContext context);
}
