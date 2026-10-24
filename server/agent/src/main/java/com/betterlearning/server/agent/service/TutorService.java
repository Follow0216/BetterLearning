package com.betterlearning.server.agent.service;

import com.betterlearning.server.agent.model.TutorModels;

public interface TutorService {
    TutorModels.Turn create(long accountId, String questionId, TutorModels.CreateRequest request);

    TutorModels.TurnList list(long accountId, String questionId);

    TutorModels.Turn get(long accountId, String questionId, String turnId);

    TutorModels.Pending pending(long accountId, String questionId);

    /** 仅在C1已完整接受并提交冻结包之后调用；retry复用原文件。 */
    TutorModels.Turn generate(long accountId, String questionId, String turnId, boolean retry);

    TutorModels.Turn cancel(long accountId, String questionId, String turnId);
}
