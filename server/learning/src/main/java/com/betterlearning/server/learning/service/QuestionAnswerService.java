package com.betterlearning.server.learning.service;

import java.util.Collection;

import com.betterlearning.server.learning.model.answer.AnswerModels;

import jakarta.servlet.http.Part;

/** 当前完整作答的持久化与认证后读取。 */
public interface QuestionAnswerService {
    AnswerModels.SaveResult save(long accountId, String questionId,
            AnswerModels.SaveManifest manifest, Collection<Part> parts);

    AnswerModels.Detail detail(long accountId, String questionId);

    AnswerModels.SaveResult changeState(long accountId, String questionId, AnswerModels.StateRequest request);

    byte[] block(long accountId, String questionId, String fileRef, int index, boolean questionRaster);
}
