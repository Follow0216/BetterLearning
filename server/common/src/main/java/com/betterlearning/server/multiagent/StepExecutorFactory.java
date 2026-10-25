package com.betterlearning.server.multiagent;

/** app提供绑定可信账号/run的执行回调，agent仅依赖此公共装配端口。 */
public interface StepExecutorFactory {
    StepExecutor forRun(long accountId, String runId);
}
