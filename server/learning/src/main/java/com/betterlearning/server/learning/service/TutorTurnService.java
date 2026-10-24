package com.betterlearning.server.learning.service;

import java.util.List;
import java.util.Optional;

import com.betterlearning.server.learning.model.dataobject.TutorTurnDO;
import com.betterlearning.server.learning.model.tutor.TutorStorageModels;

/** 每次状态写入为短事务；不包含模型调用或文件上传IO。 */
public interface TutorTurnService {
    TutorStorageModels.Creation createOrGet(long accountId, String questionId, String requestId, String prompt);
    List<TutorTurnDO> list(long accountId, String questionId);
    TutorTurnDO get(long accountId, String questionId, String turnId);
    Optional<TutorTurnDO> pending(long accountId, String questionId);
    TutorStorageModels.Acceptance bind(long accountId, String questionId, String turnId, String fileRef, String hash);
    TutorStorageModels.Claim claim(long accountId, String questionId, String turnId, boolean retry);
    TutorTurnDO finish(long accountId, String questionId, String turnId, String fileRef,
            String answer, String failureCode, String failureMessage);
    TutorTurnDO cancel(long accountId, String questionId, String turnId);
}
