package com.betterlearning.server.agent;

import java.util.Collection;

import com.betterlearning.server.learning.model.answer.AnswerModels;

import jakarta.servlet.http.Part;

/** V2 请求业务入口，不调用 V1 的同步模型执行器。 */
public interface TutorRequestService {
    TutorRequestModels.Request create(long accountId, String questionId, String idempotencyKey,
            TutorRequestModels.CreateRequest input);

    TutorRequestModels.Page list(long accountId, String questionId, String cursor, Integer limit);

    TutorRequestModels.Pending pending(long accountId, String questionId);

    TutorRequestModels.Detail detail(long accountId, String questionId, String requestId);

    TutorRequestModels.Detail upload(long accountId, String questionId, String requestId,
            AnswerModels.SaveManifest manifest, Collection<Part> parts);

    TutorRequestModels.Detail cancel(long accountId, String questionId, String requestId,
            TutorRequestModels.CancelRequest input);

    TutorRequestModels.Detail retry(long accountId, String questionId, String requestId,
            String idempotencyKey, TutorRequestModels.RetryRequest input);
}
