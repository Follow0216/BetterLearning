package com.betterlearning.server.agent.model;

import com.betterlearning.server.agent.model.BatchOcrModels.Result;

/** 取消时携带已经识别的文本与可校对来源，不将取消误报为空结果。 */
public final class BatchOcrCancelledException extends RuntimeException {
    private final Result partialResult;

    public BatchOcrCancelledException(Result partialResult) {
        super("批量识别已取消");
        this.partialResult = partialResult;
    }

    public Result partialResult() {
        return partialResult;
    }
}
