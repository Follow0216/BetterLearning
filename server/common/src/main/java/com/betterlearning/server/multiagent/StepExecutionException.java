package com.betterlearning.server.multiagent;

/** 仅保存脱敏执行原因，不携带供应商异常链或输入正文。 */
public final class StepExecutionException extends RuntimeException {
    private final String reason;

    public StepExecutionException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
