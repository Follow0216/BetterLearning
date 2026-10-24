package com.betterlearning.server.learning.service;

import java.util.Collection;

import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.model.tutor.TutorStorageModels;

import jakarta.servlet.http.Part;

/** 每轮独立且只绑定一次的作答包，不接M2当前引用作为快照。 */
public interface FrozenAnswerService {
    TutorStorageModels.Acceptance accept(long accountId, String questionId, String turnId,
            AnswerModels.SaveManifest manifest, Collection<Part> parts);

    AnswerModels.ReadManifest manifest(long accountId, String questionId, String turnId);

    byte[] block(long accountId, String questionId, String turnId, String fileRef, int index, boolean questionRaster);

    TutorStorageModels.Frozen modelInput(long accountId, String questionId, String turnId);
}
