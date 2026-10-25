package com.betterlearning.server.agent;

import com.betterlearning.server.multiagent.MultiAgentModels.*;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.multiagent.StepExecutor;
import com.betterlearning.server.multiagent.TutorLedger;

/** 收费调用的短事务前后边界；整个回调不持有数据库事务。 */
public final class LedgerStepExecutor implements StepExecutor {
    private final TutorLedger ledger;
    private final long accountId;
    private final String runId;

    public LedgerStepExecutor(TutorLedger ledger, long accountId, String runId) {
        this.ledger = ledger;
        this.accountId = accountId;
        this.runId = runId;
    }

    @Override
    public StepOutput execute(StepInvocation invocation, StepAction action) {
        if (invocation == null || action == null) {
            throw new IllegalArgumentException("step invocation required");
        }
        Execution execution = ledger.execution(accountId, runId);
        if (execution.status() != Status.RUNNING || execution.cancelRequestedAt() != null) {
            throw new StepExecutionException("RUN_NOT_ACTIVE");
        }
        Attempt attempt = ledger.beginAttempt(accountId, runId, invocation.role(), invocation.sequence(), invocation.inputDigest());
        if ("CONFIRMED".equals(attempt.providerCallState())) {
            return new StepOutput(attempt.output(), attempt.outputDigest());
        }
        if (!"NOT_SENT".equals(attempt.providerCallState()) || !"RUNNING".equals(attempt.status())) {
            throw new StepExecutionException("OUTCOME_UNKNOWN");
        }
        boolean charged = invocation.role() != Role.RETRIEVE;
        if (charged && !ledger.callIntent(accountId, runId, attempt.attemptId(), attempt.fence())) {
            throw new StepExecutionException("CALL_NOT_ADMITTED");
        }
        StepOutput output;
        try {
            output = action.call();
        } catch (RuntimeException exception) {
            if (charged) {
                // 异常不证明供应商已结束；保留执行预算直至明确确认或执行deadline。
                ledger.unknown(accountId, runId, attempt.attemptId(), attempt.fence());
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
            // 检索有独立收费账本；只传播明确允许的原因，不复制供应商异常链。
            String reason = exception instanceof StepExecutionException controlled
                    && "OUTCOME_UNKNOWN".equals(controlled.reason()) ? "OUTCOME_UNKNOWN" : "RETRIEVAL_FAILED";
            Execution current = ledger.execution(accountId, runId);
            ledger.fail(accountId, runId, current.revision(), reason);
            throw new StepExecutionException(reason);
        }
        try {
            if (output == null || !ledger.confirm(accountId, runId, attempt.attemptId(), attempt.fence(),
                    output.output(), output.outputDigest())) {
                throw new StepExecutionException("RESULT_FENCED");
            }
            return output;
        } finally {
            // action正常返回确认外部调用结束；取消已废止发布权也不能永久占用执行预算。
            if (charged) {
                ledger.finishProviderCall(accountId, runId, attempt.attemptId());
            }
        }
    }
}
