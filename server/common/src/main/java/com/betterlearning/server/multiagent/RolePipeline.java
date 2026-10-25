package com.betterlearning.server.multiagent;

import com.betterlearning.server.multiagent.MultiAgentModels.PipelineContext;
import com.betterlearning.server.multiagent.MultiAgentModels.PipelineResult;

/** 四角色有限流水线；逐角色执行必须经运行时回调复用已确认输出与检查取消/预算。 */
public interface RolePipeline {
    PipelineResult execute(PipelineContext context, StepExecutor steps);
}
