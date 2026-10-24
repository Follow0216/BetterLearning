package com.betterlearning.server.learning.service.impl;

import java.util.Objects;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.dao.QuestionAnswerDao;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.model.dataobject.QuestionAnswerDO;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 独立事务边界；Spring 代理提交成功后调用方才收到 fileRef。 */
@Service
public class AnswerPublisher {
    private final QuestionAnswerDao dao;

    public AnswerPublisher(QuestionAnswerDao dao) {
        this.dao = dao;
    }

    public record Published(QuestionAnswerDO answer, String retiredFileRef) {
    }

    @Transactional
    public Published publish(long accountId, long questionId, AnswerModels.SaveManifest manifest,
            String requestHash, String candidateRef) {
        lock(accountId, questionId);
        QuestionAnswerDO current = dao.find(accountId, questionId).orElse(null);
        if (current != null && current.getSaveRequestId().equals(manifest.saveRequestId())) {
            if (!current.getRequestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.ANSWER_SAVE_CONFLICT);
            }
            return new Published(current, null);
        }
        String currentRef = current == null ? null : current.getFileRef();
        if (!Objects.equals(currentRef, manifest.baseFileRef())) {
            throw new BusinessException(ErrorCode.ANSWER_CHANGED);
        }
        if (current != null && "COMPLETED".equals(current.getState())) {
            throw new BusinessException(ErrorCode.ANSWER_COMPLETED);
        }
        QuestionAnswerDO candidate = new QuestionAnswerDO();
        candidate.setAccountId(accountId);
        candidate.setQuestionId(questionId);
        candidate.setFileRef(candidateRef);
        candidate.setSaveRequestId(manifest.saveRequestId());
        candidate.setRequestHash(requestHash);
        return new Published(dao.publish(candidate), currentRef);
    }

    @Transactional
    public QuestionAnswerDO changeState(long accountId, long questionId, AnswerModels.StateRequest request) {
        if (request == null || !AnswerManifestValidator.uuid(request.fileRef())
                || !("IN_PROGRESS".equals(request.state()) || "COMPLETED".equals(request.state()))) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        lock(accountId, questionId);
        QuestionAnswerDO current = dao.find(accountId, questionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANSWER_NOT_SAVED));
        if (!current.getFileRef().equals(request.fileRef())) {
            throw new BusinessException(ErrorCode.ANSWER_CHANGED);
        }
        if (current.getState().equals(request.state())) {
            return current;
        }
        if (dao.updateState(accountId, questionId, request.fileRef(), request.state()) != 1) {
            throw new BusinessException(ErrorCode.ANSWER_CHANGED);
        }
        return dao.find(accountId, questionId).orElseThrow();
    }

    private void lock(long accountId, long questionId) {
        if (!dao.lockQuestion(accountId, questionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }
}
