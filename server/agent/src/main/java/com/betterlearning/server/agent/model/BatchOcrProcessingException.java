package com.betterlearning.server.agent.model;

import com.betterlearning.server.agent.model.AgentModelException.Failure;
import com.betterlearning.server.agent.model.BatchOcrModels.Result;

/** 供应商失败只透传预定义错误与已完成文本，不携带原始异常。 */
public final class BatchOcrProcessingException extends RuntimeException {
    private final Failure failure;
    private final Result partialResult;

    public BatchOcrProcessingException(Failure failure, Result partialResult) {
        super(failure.message());
        this.failure = failure;
        this.partialResult = partialResult;
    }

    public Failure failure() {
        return failure;
    }

    public Result partialResult() {
        return partialResult;
    }
}
