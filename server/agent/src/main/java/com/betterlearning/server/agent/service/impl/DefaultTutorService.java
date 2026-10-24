package com.betterlearning.server.agent.service.impl;

import java.util.UUID;

import com.betterlearning.server.agent.model.AgentModelException;
import com.betterlearning.server.agent.model.AgentModelException.Failure;
import com.betterlearning.server.agent.model.TutorModels;
import com.betterlearning.server.agent.service.TutorService;
import com.betterlearning.server.agent.service.TutorTurnStore;
import com.betterlearning.server.agent.service.TutoringModel;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;

import org.springframework.beans.factory.ObjectProvider;

/** 同步、有界的轮次编排；不在模型调用期间开启数据库事务或另起后台任务。 */
public class DefaultTutorService implements TutorService {
    private final TutorTurnStore store;
    private final ObjectProvider<TutoringModel> models;

    public DefaultTutorService(TutorTurnStore store, ObjectProvider<TutoringModel> models) {
        this.store = store;
        this.models = models;
    }

    @Override
    public TutorModels.Turn create(long accountId, String questionId, TutorModels.CreateRequest request) {
        questionId(questionId);
        if (request == null || !uuid(request.requestId()) || request.prompt() == null
                || request.prompt().isBlank() || request.prompt().length() > 4000) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        return store.createOrGet(accountId, questionId, request.requestId(), request.prompt());
    }

    @Override
    public TutorModels.TurnList list(long accountId, String questionId) {
        questionId(questionId);
        return new TutorModels.TurnList(store.list(accountId, questionId));
    }

    @Override
    public TutorModels.Turn get(long accountId, String questionId, String turnId) {
        ids(questionId, turnId);
        return store.get(accountId, questionId, turnId);
    }

    @Override
    public TutorModels.Pending pending(long accountId, String questionId) {
        questionId(questionId);
        return new TutorModels.Pending(store.pending(accountId, questionId).orElse(null));
    }

    @Override
    public TutorModels.Turn generate(long accountId, String questionId, String turnId, boolean retry) {
        ids(questionId, turnId);
        TutorModels.Turn current = store.get(accountId, questionId, turnId);
        if (current.status() == TutorModels.Status.GENERATING || current.status() == TutorModels.Status.SUCCEEDED) {
            return current;
        }
        if (current.fileRef() == null) {
            throw new BusinessException(ErrorCode.ANSWER_NOT_SAVED);
        }
        // 先读取完整冻结包再领取，普通文件/DB读取失败不会把状态留在GENERATING。
        TutorModels.FrozenAnswer frozen = store.frozenAnswer(accountId, questionId, turnId);
        if (!current.fileRef().equals(frozen.fileRef()) || frozen.images().isEmpty()) {
            throw new BusinessException(ErrorCode.ANSWER_NOT_SAVED);
        }
        TutorModels.GenerationClaim claim = store.claimGeneration(accountId, questionId, turnId, retry);
        if (!claim.claimed()) {
            return claim.turn();
        }
        if (!frozen.fileRef().equals(claim.turn().fileRef())) {
            throw new IllegalStateException("不可变轮次文件引用被替换");
        }
        String answer;
        try {
            TutoringModel model = models.getIfAvailable();
            if (model == null) {
                throw new AgentModelException(Failure.MODEL_NOT_CONFIGURED);
            }
            answer = model.answer(claim.turn().prompt(), frozen);
            if (answer == null || answer.isBlank()) {
                throw new AgentModelException(Failure.MODEL_EMPTY_RESPONSE);
            }
        } catch (AgentModelException exception) {
            return store.fail(accountId, questionId, turnId, frozen.fileRef(),
                    exception.failure().name(), exception.failure().message());
        } catch (RuntimeException exception) {
            // 不保存供应商异常信息，避免响应体/凭据泄漏；失败仍绑定原冻结文件。
            return store.fail(accountId, questionId, turnId, frozen.fileRef(),
                    Failure.MODEL_UNAVAILABLE.name(), Failure.MODEL_UNAVAILABLE.message());
        }
        // 数据库发布失败不可被上方模型catch吞掉；未知提交由原轮查询确认。
        return store.succeed(accountId, questionId, turnId, frozen.fileRef(), answer);
    }

    @Override
    public TutorModels.Turn cancel(long accountId, String questionId, String turnId) {
        ids(questionId, turnId);
        return store.cancelWaiting(accountId, questionId, turnId);
    }

    private static void ids(String questionId, String turnId) {
        questionId(questionId);
        if (!uuid(turnId)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }

    private static void questionId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        try {
            Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }

    private static boolean uuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
