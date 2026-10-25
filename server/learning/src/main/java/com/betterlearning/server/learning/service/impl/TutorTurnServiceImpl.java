package com.betterlearning.server.learning.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.dao.TutorTurnDao;
import com.betterlearning.server.learning.model.dataobject.TutorTurnDO;
import com.betterlearning.server.learning.model.tutor.TutorStorageModels;
import com.betterlearning.server.learning.service.TutorTurnService;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 所有轮次写入串行化同一账号，数据库约束兜底，网络模型调用在事务外。 */
@Service
public class TutorTurnServiceImpl implements TutorTurnService {
    private final LearningDao questions;
    private final TutorTurnDao turns;

    public TutorTurnServiceImpl(LearningDao questions, TutorTurnDao turns) {
        this.questions = questions;
        this.turns = turns;
    }

    @Override
    @Transactional
    public TutorStorageModels.Creation createOrGet(long accountId, String questionId, String requestId, String prompt) {
        if (!AnswerManifestValidator.uuid(requestId) || prompt == null || prompt.isBlank() || prompt.length() > 4000) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        long id = id(questionId);
        lock(accountId, id);
        TutorTurnDO existing = turns.findRequest(accountId, requestId).orElse(null);
        if (existing != null) {
            if (!existing.getQuestionId().equals(id) || !existing.getPrompt().equals(prompt)) {
                throw conflict();
            }
            return new TutorStorageModels.Creation(existing, false);
        }
        requireNoOtherActive(accountId, null);
        TutorTurnDO turn = new TutorTurnDO();
        turn.setTurnId(UUID.randomUUID().toString());
        turn.setAccountId(accountId);
        turn.setQuestionId(id);
        turn.setRequestId(requestId);
        turn.setPrompt(prompt);
        return new TutorStorageModels.Creation(turns.insert(turn), true);
    }

    @Override
    public List<TutorTurnDO> list(long accountId, String questionId) {
        long id = id(questionId);
        authorize(accountId, id);
        return turns.recent(accountId, id);
    }

    @Override
    public TutorTurnDO get(long accountId, String questionId, String turnId) {
        long id = id(questionId);
        uuid(turnId);
        authorize(accountId, id);
        return find(accountId, id, turnId);
    }

    @Override
    public Optional<TutorTurnDO> pending(long accountId, String questionId) {
        long id = id(questionId);
        authorize(accountId, id);
        return turns.active(accountId).filter(turn -> turn.getQuestionId().equals(id)
                && "WAITING_ANSWER".equals(turn.getStatus()) && turn.getFileRef() == null);
    }

    @Override
    @Transactional
    public TutorStorageModels.Acceptance bind(long accountId, String questionId, String turnId, String fileRef, String hash) {
        long id = id(questionId);
        uuid(turnId);
        uuid(fileRef);
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        lock(accountId, id);
        TutorTurnDO turn = find(accountId, id, turnId);
        if (turn.getFileRef() != null) {
            if (!Objects.equals(hash, turn.getRequestHash())) {
                throw new BusinessException(ErrorCode.ANSWER_SAVE_CONFLICT);
            }
            return new TutorStorageModels.Acceptance(turn, false);
        }
        if (!"WAITING_ANSWER".equals(turn.getStatus()) || turns.bind(accountId, id, turnId, fileRef, hash) != 1) {
            throw conflict();
        }
        return new TutorStorageModels.Acceptance(find(accountId, id, turnId), true);
    }

    @Override
    @Transactional
    public TutorStorageModels.Claim claim(long accountId, String questionId, String turnId, boolean retry) {
        long id = id(questionId);
        uuid(turnId);
        lock(accountId, id);
        TutorTurnDO turn = find(accountId, id, turnId);
        if ("GENERATING".equals(turn.getStatus()) || "SUCCEEDED".equals(turn.getStatus())) {
            return new TutorStorageModels.Claim(turn, false);
        }
        if (turn.getFileRef() == null) {
            throw new BusinessException(ErrorCode.ANSWER_NOT_SAVED);
        }
        if (!(retry ? "FAILED" : "WAITING_ANSWER").equals(turn.getStatus())) {
            throw conflict();
        }
        requireNoOtherActive(accountId, turnId);
        if (turns.claim(accountId, id, turnId, turn.getStatus()) != 1) {
            throw conflict();
        }
        return new TutorStorageModels.Claim(find(accountId, id, turnId), true);
    }

    @Override
    @Transactional
    public TutorTurnDO finish(long accountId, String questionId, String turnId, String fileRef,
            String answer, String failureCode, String failureMessage) {
        long id = id(questionId);
        uuid(turnId);
        uuid(fileRef);
        lock(accountId, id);
        TutorTurnDO turn = find(accountId, id, turnId);
        if (!Objects.equals(fileRef, turn.getFileRef())) {
            throw conflict();
        }
        if (!"GENERATING".equals(turn.getStatus())) {
            if ("SUCCEEDED".equals(turn.getStatus()) || "FAILED".equals(turn.getStatus())) {
                return turn;
            }
            throw conflict();
        }
        boolean success = answer != null && !answer.isBlank();
        if (success ? failureCode != null || failureMessage != null
                : answer != null || failureCode == null || failureCode.isBlank() || failureMessage == null || failureMessage.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        if (turns.finish(accountId, id, turnId, fileRef, success ? "SUCCEEDED" : "FAILED",
                answer, failureCode, failureMessage) != 1) {
            throw conflict();
        }
        return find(accountId, id, turnId);
    }

    @Override
    @Transactional
    public TutorTurnDO cancel(long accountId, String questionId, String turnId) {
        long id = id(questionId);
        uuid(turnId);
        lock(accountId, id);
        TutorTurnDO turn = find(accountId, id, turnId);
        if ("GENERATING".equals(turn.getStatus())) {
            throw conflict();
        }
        if ("WAITING_ANSWER".equals(turn.getStatus())) {
            if (turn.getFileRef() != null || turns.cancel(accountId, id, turnId) != 1) {
                throw conflict();
            }
            return find(accountId, id, turnId);
        }
        return turn;
    }

    private void requireNoOtherActive(long accountId, String exceptTurn) {
        if (turns.activeRequest(accountId)
                || turns.active(accountId).filter(turn -> !turn.getTurnId().equals(exceptTurn)).isPresent()) {
            throw conflict();
        }
    }

    private TutorTurnDO find(long accountId, long questionId, String turnId) {
        return turns.find(accountId, questionId, turnId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void lock(long accountId, long questionId) {
        if (!turns.lockAccount(accountId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        authorize(accountId, questionId);
    }

    private void authorize(long accountId, long questionId) {
        questions.findById(accountId, questionId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }

    private static void uuid(String value) {
        if (!AnswerManifestValidator.uuid(value)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.TUTOR_TURN_CONFLICT);
    }
}
