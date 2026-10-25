package com.betterlearning.server.multiagent;

import com.betterlearning.server.multiagent.MultiAgentModels.StepInvocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StepOutput;

/** 运行时提供的逐角色持久边界，不得直接从流水线调用收费模型。 */
public interface StepExecutor {
    /**
     * 已CONFIRMED时返回已存输出且不调用action；CALL_INTENT未确认时原子标UNKNOWN并终结run。
     * 新调用先复核当前run/取消/期限/预算并提交CALL_INTENT，再执行action，确认后持久化结果。
     * action的异常不可盲重试；不能证明未发送必须unknown。RETRIEVE不计生成模型预算。
     */
    StepOutput execute(StepInvocation invocation, StepAction action);

    @FunctionalInterface
    interface StepAction {
        StepOutput call();
    }
}
