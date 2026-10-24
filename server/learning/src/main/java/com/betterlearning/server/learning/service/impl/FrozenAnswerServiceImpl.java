package com.betterlearning.server.learning.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.converter.AnswerConverter;
import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.model.dataobject.QuestionDO;
import com.betterlearning.server.learning.model.dataobject.TutorTurnDO;
import com.betterlearning.server.learning.model.tutor.TutorStorageModels;
import com.betterlearning.server.learning.service.FrozenAnswerService;
import com.betterlearning.server.learning.service.TutorTurnService;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;

import jakarta.servlet.http.Part;

import org.springframework.stereotype.Service;

/** 完整文件先落盘，再经独立事务代理绑定轮次；此类不持有数据库事务。 */
@Service
public class FrozenAnswerServiceImpl implements FrozenAnswerService {
    private final TutorTurnService turns;
    private final LearningDao questions;
    private final AnswerFileStore files;

    public FrozenAnswerServiceImpl(TutorTurnService turns, LearningDao questions, AnswerFileStore files) {
        this.turns = turns;
        this.questions = questions;
        this.files = files;
    }

    @Override
    public TutorStorageModels.Acceptance accept(long accountId, String questionId, String turnId,
            AnswerModels.SaveManifest manifest, Collection<Part> parts) {
        TutorTurnDO turn = turns.get(accountId, questionId, turnId);
        if (manifest == null || !turnId.equals(manifest.saveRequestId()) || manifest.baseFileRef() != null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        if (turn.getFileRef() == null && !"WAITING_ANSWER".equals(turn.getStatus())) {
            throw new BusinessException(ErrorCode.TUTOR_TURN_CONFLICT);
        }
        QuestionDO question = questions.findById(accountId, turn.getQuestionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        AnswerManifestValidator.validate(manifest, question.getBodyMarkdown());
        AnswerFileStore.Candidate candidate = files.save(manifest, new AnswerModels.QuestionSnapshot(
                questionId, question.getSubject(), question.getBodyMarkdown()), parts);
        try {
            TutorStorageModels.Acceptance result = turns.bind(accountId, questionId, turnId,
                    candidate.fileRef(), candidate.requestHash());
            if (!candidate.fileRef().equals(result.turn().getFileRef())) {
                files.discard(candidate);
            }
            return result;
        } catch (RuntimeException exception) {
            try {
                if (!candidate.fileRef().equals(turns.get(accountId, questionId, turnId).getFileRef())) {
                    files.discard(candidate);
                }
            } catch (RuntimeException lookupFailure) {
                // 提交结果未知且无法确认引用时保留可能已提交候选，绝不误删冻结文件。
            }
            throw exception;
        }
    }

    @Override
    public AnswerModels.ReadManifest manifest(long accountId, String questionId, String turnId) {
        TutorTurnDO turn = accepted(accountId, questionId, turnId);
        String base = "/api/v1/learning/questions/" + questionId + "/tutor-turns/" + turnId
                + "/answer/files/" + turn.getFileRef();
        return AnswerConverter.read(files.manifest(turn.getFileRef()), base);
    }

    @Override
    public byte[] block(long accountId, String questionId, String turnId, String fileRef, int index, boolean questionRaster) {
        TutorTurnDO turn = turns.get(accountId, questionId, turnId);
        if (index < 0 || !AnswerManifestValidator.uuid(fileRef)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        if (!fileRef.equals(turn.getFileRef())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        AnswerModels.SaveManifest source = files.manifest(fileRef).source();
        List<AnswerModels.Block> blocks = questionRaster ? source.document().questionLayout().rasterBlocks() : source.blocks();
        if (index >= blocks.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return files.block(fileRef, blocks.get(index).partName());
    }

    @Override
    public TutorStorageModels.Frozen modelInput(long accountId, String questionId, String turnId) {
        TutorTurnDO turn = accepted(accountId, questionId, turnId);
        AnswerModels.StoredManifest stored = files.manifest(turn.getFileRef());
        List<TutorStorageModels.Image> images = new ArrayList<>();
        for (AnswerModels.Block block : stored.source().blocks()) {
            String position = "index=" + block.index() + ", x=" + block.x() + ", y=" + block.y()
                    + ", width=" + block.width() + ", height=" + block.height();
            images.add(new TutorStorageModels.Image(files.block(turn.getFileRef(), block.partName()), position));
        }
        return new TutorStorageModels.Frozen(turn.getFileRef(), stored.questionSnapshot().bodyMarkdown(), List.copyOf(images));
    }

    private TutorTurnDO accepted(long accountId, String questionId, String turnId) {
        TutorTurnDO turn = turns.get(accountId, questionId, turnId);
        if (turn.getFileRef() == null) {
            throw new BusinessException(ErrorCode.ANSWER_NOT_SAVED);
        }
        return turn;
    }
}
