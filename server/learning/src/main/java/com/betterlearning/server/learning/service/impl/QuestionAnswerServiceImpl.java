package com.betterlearning.server.learning.service.impl;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.converter.AnswerConverter;
import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.dao.QuestionAnswerDao;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.model.dataobject.QuestionAnswerDO;
import com.betterlearning.server.learning.model.dataobject.QuestionDO;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.learning.service.QuestionAnswerService;
import com.betterlearning.server.multiagent.SourceStore;

import jakarta.servlet.http.Part;

import org.springframework.stereotype.Service;

/** 文件完整发布与数据库提交编排；不在上传 I/O 期间持有题目行锁。 */
@Service
public class QuestionAnswerServiceImpl implements QuestionAnswerService {
    private final LearningDao learningDao;
    private final QuestionAnswerDao answerDao;
    private final AnswerPublisher publisher;
    private final AnswerFileStore store;
    private final SourceStore sourceStore;

    public QuestionAnswerServiceImpl(LearningDao learningDao, QuestionAnswerDao answerDao,
            AnswerPublisher publisher, AnswerFileStore store, SourceStore sourceStore) {
        this.learningDao = learningDao;
        this.answerDao = answerDao;
        this.publisher = publisher;
        this.store = store;
        this.sourceStore = sourceStore;
    }

    public AnswerModels.SaveResult save(long accountId, String questionId,
            AnswerModels.SaveManifest manifest, Collection<Part> parts) {
        long id = id(questionId);
        QuestionDO question = question(accountId, id);
        AnswerManifestValidator.validate(manifest, question.getBodyMarkdown());
        AnswerModels.QuestionSnapshot snapshot = new AnswerModels.QuestionSnapshot(
                questionId, question.getSubject(), question.getBodyMarkdown());
        AnswerFileStore.Candidate candidate = store.save(manifest, snapshot, parts);
        try {
            AnswerPublisher.Published published = publisher.publish(accountId, id, manifest,
                    candidate.requestHash(), candidate.fileRef());
            QuestionAnswerDO result = published.answer();
            if (!candidate.fileRef().equals(result.getFileRef())) {
                store.discard(candidate);
            }
            // 已发布的旧 fileRef 可能被不可变资料版本引用，不能由 M2 覆盖流程清理。
            return result(result);
        } catch (RuntimeException exception) {
            // 提交结果未知时不可删掉可能已经提交的文件；仅在读回确认未引用后清本候选。
            try {
                boolean referenced = answerDao.find(accountId, id)
                        .map(answer -> candidate.fileRef().equals(answer.getFileRef())).orElse(false);
                if (!referenced && !sourceStore.fileReferenced(candidate.fileRef())) {
                    store.discard(candidate);
                }
            } catch (RuntimeException lookupFailure) {
                // DB 不可用，保留不可确定的候选；原异常仍传播，不伪报成功。
            }
            throw exception;
        }
    }

    public AnswerModels.Detail detail(long accountId, String questionId) {
        long id = id(questionId);
        question(accountId, id);
        QuestionAnswerDO answer = answerDao.find(accountId, id).orElse(null);
        if (answer == null) {
            return new AnswerModels.Detail(questionId, "NOT_STARTED", null, null, null, null);
        }
        AnswerModels.StoredManifest stored;
        try {
            stored = store.manifest(answer.getFileRef());
        } catch (BusinessException exception) {
            ensureCurrent(accountId, id, answer.getFileRef());
            throw exception;
        }
        answer = ensureCurrent(accountId, id, answer.getFileRef());
        String base = "/api/v1/learning/questions/" + questionId + "/answer/files/" + answer.getFileRef();
        AnswerModels.ReadManifest manifest = AnswerConverter.read(stored, base);
        return new AnswerModels.Detail(questionId, answer.getState(), answer.getFileRef(),
                answer.getSavedAt(), answer.getCompletedAt(), manifest);
    }

    public AnswerModels.SaveResult changeState(long accountId, String questionId, AnswerModels.StateRequest request) {
        return result(publisher.changeState(accountId, id(questionId), request));
    }

    public byte[] block(long accountId, String questionId, String fileRef, int index, boolean questionRaster) {
        long id = id(questionId);
        if (index < 0 || !AnswerManifestValidator.uuid(fileRef)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        QuestionAnswerDO current = answerDao.find(accountId, id)
                .filter(answer -> Objects.equals(fileRef, answer.getFileRef()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        try {
            AnswerModels.SaveManifest source = store.manifest(current.getFileRef()).source();
            List<AnswerModels.Block> blocks = questionRaster ? source.document().questionLayout().rasterBlocks() : source.blocks();
            if (index >= blocks.size()) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            byte[] bytes = store.block(fileRef, blocks.get(index).partName());
            requireReadableCurrent(accountId, id, fileRef);
            return bytes;
        } catch (BusinessException exception) {
            requireReadableCurrent(accountId, id, fileRef);
            throw exception;
        }
    }

    private void requireReadableCurrent(long accountId, long questionId, String fileRef) {
        if (answerDao.find(accountId, questionId).filter(answer -> fileRef.equals(answer.getFileRef())).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private QuestionAnswerDO ensureCurrent(long accountId, long questionId, String fileRef) {
        return answerDao.find(accountId, questionId).filter(answer -> fileRef.equals(answer.getFileRef()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ANSWER_CHANGED));
    }

    private QuestionDO question(long accountId, long id) {
        return learningDao.findById(accountId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static AnswerModels.SaveResult result(QuestionAnswerDO answer) {
        return new AnswerModels.SaveResult(Long.toString(answer.getQuestionId()), answer.getState(),
                answer.getFileRef(), answer.getSavedAt(), answer.getCompletedAt());
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
}
