package com.betterlearning.server.agent;

import java.util.List;
import java.util.Optional;

import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.model.TutorModels;
import com.betterlearning.server.agent.service.TutorTurnStore;
import com.betterlearning.server.learning.model.dataobject.TutorTurnDO;
import com.betterlearning.server.learning.model.tutor.TutorStorageModels;
import com.betterlearning.server.learning.service.FrozenAnswerService;
import com.betterlearning.server.learning.service.TutorTurnService;

import org.springframework.stereotype.Component;

/** app显式衔接两业务模块，双方不建立反向或互相依赖。 */
@Component
public class TutorTurnStoreAdapter implements TutorTurnStore {
    private final TutorTurnService turns;
    private final FrozenAnswerService frozen;

    public TutorTurnStoreAdapter(TutorTurnService turns, FrozenAnswerService frozen) {
        this.turns = turns;
        this.frozen = frozen;
    }

    @Override
    public TutorModels.Turn createOrGet(long accountId, String questionId, String requestId, String prompt) {
        return turn(turns.createOrGet(accountId, questionId, requestId, prompt).turn());
    }

    @Override
    public List<TutorModels.Turn> list(long accountId, String questionId) {
        return turns.list(accountId, questionId).stream().map(TutorTurnStoreAdapter::turn).toList();
    }

    @Override
    public TutorModels.Turn get(long accountId, String questionId, String turnId) {
        return turn(turns.get(accountId, questionId, turnId));
    }

    @Override
    public Optional<TutorModels.Turn> pending(long accountId, String questionId) {
        return turns.pending(accountId, questionId).map(TutorTurnStoreAdapter::turn);
    }

    @Override
    public TutorModels.GenerationClaim claimGeneration(long accountId, String questionId, String turnId, boolean retry) {
        TutorStorageModels.Claim claim = turns.claim(accountId, questionId, turnId, retry);
        return new TutorModels.GenerationClaim(turn(claim.turn()), claim.claimed());
    }

    @Override
    public TutorModels.FrozenAnswer frozenAnswer(long accountId, String questionId, String turnId) {
        TutorStorageModels.Frozen snapshot = frozen.modelInput(accountId, questionId, turnId);
        List<ModelImage> images = snapshot.images().stream()
                .map(image -> new ModelImage("image/png", image.bytes(), image.position())).toList();
        return new TutorModels.FrozenAnswer(snapshot.fileRef(), snapshot.questionMarkdown(), images);
    }

    @Override
    public TutorModels.Turn succeed(long accountId, String questionId, String turnId, String fileRef, String answerMarkdown) {
        return turn(turns.finish(accountId, questionId, turnId, fileRef, answerMarkdown, null, null));
    }

    @Override
    public TutorModels.Turn fail(long accountId, String questionId, String turnId, String fileRef,
            String failureCode, String failureMessage) {
        return turn(turns.finish(accountId, questionId, turnId, fileRef, null, failureCode, failureMessage));
    }

    @Override
    public TutorModels.Turn cancelWaiting(long accountId, String questionId, String turnId) {
        return turn(turns.cancel(accountId, questionId, turnId));
    }

    public static TutorModels.Turn turn(TutorTurnDO row) {
        return new TutorModels.Turn(row.getTurnId(), row.getRequestId(), Long.toString(row.getQuestionId()),
                row.getPrompt(), TutorModels.Status.valueOf(row.getStatus()), row.getFileRef(), row.getAnswerMarkdown(),
                row.getFailureCode(), row.getFailureMessage(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
