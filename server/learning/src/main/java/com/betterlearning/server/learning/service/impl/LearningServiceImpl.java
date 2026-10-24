package com.betterlearning.server.learning.service.impl;

import java.util.ArrayList;
import java.util.List;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.converter.QuestionConverter;
import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.exception.CollectionNameConflictException;
import com.betterlearning.server.learning.model.dataobject.QuestionCollectionDO;
import com.betterlearning.server.learning.model.dto.CreateQuestionCollectionDTO;
import com.betterlearning.server.learning.model.dto.RenameQuestionCollectionDTO;
import com.betterlearning.server.learning.model.dto.MoveQuestionDTO;
import com.betterlearning.server.learning.model.dataobject.QuestionDO;
import com.betterlearning.server.learning.model.dto.ConfirmQuestionDTO;
import com.betterlearning.server.learning.model.vo.QuestionCollectionsVO;
import com.betterlearning.server.learning.model.vo.QuestionCollectionVO;
import com.betterlearning.server.learning.model.vo.QuestionPageVO;
import com.betterlearning.server.learning.model.vo.QuestionVO;
import com.betterlearning.server.learning.service.LearningService;
import com.betterlearning.server.learning.validation.MarkdownSummary;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 当前账号题库服务；事务内原子去重，支持题集树与单题整理，不编辑正文。 */
@Service
public class LearningServiceImpl implements LearningService {

    private static final int MAX_PAGE = 1000000;
    private static final int MAX_PAGE_SIZE = 100;

    private final LearningDao dao;

    public LearningServiceImpl(LearningDao dao) {
        this.dao = dao;
    }

    /** READ_COMMITTED 确保冲突后的下一条查询看见已提交的去重记录。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuestionVO confirm(long accountId, ConfirmQuestionDTO request) {
        QuestionDO question = new QuestionDO();
        question.setAccountId(accountId);
        question.setRequestId(request.requestId());
        question.setSubject(request.subject());
        question.setBodyMarkdown(request.bodyMarkdown());
        question.setSummary(MarkdownSummary.from(request.bodyMarkdown()));
        Long insertedId = dao.insertIfAbsent(question);
        QuestionDO persisted = dao.findByRequestId(accountId, request.requestId())
                .orElseThrow(() -> new IllegalStateException("确认入库后未读取到记录"));
        if (insertedId == null && (!persisted.getBodyMarkdown().equals(request.bodyMarkdown())
                || !persisted.getSubject().equals(request.subject()))) {
            throw new BusinessException(ErrorCode.CONFIRM_REQUEST_CONFLICT);
        }
        return QuestionConverter.detail(persisted);
    }

    @Override
    @Transactional(readOnly = true)
    public QuestionVO detail(long accountId, String questionId) {
        return QuestionConverter.detail(dao.findById(accountId, positiveId(questionId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QuestionPageVO page(long accountId, String collectionId, int page, int pageSize) {
        if (page < 1 || page > MAX_PAGE || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        Long persistedCollectionId = null;
        boolean filtered = collectionId != null;
        if (filtered && !QuestionConverter.INBOX_ID.equals(collectionId)) {
            persistedCollectionId = positiveId(collectionId);
            if (!dao.collectionExists(accountId, persistedCollectionId)) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
        }
        long total = dao.count(accountId, filtered, persistedCollectionId);
        long offset = (long) (page - 1) * pageSize;
        return new QuestionPageVO(dao.findPage(accountId, filtered, persistedCollectionId, offset, pageSize)
                .stream().map(QuestionConverter::summary).toList(), page, pageSize, total);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QuestionCollectionsVO collections(long accountId) {
        List<QuestionCollectionVO> items = new ArrayList<>();
        items.add(new QuestionCollectionVO(QuestionConverter.INBOX_ID, "待整理", true,
                dao.count(accountId, true, null)));
        dao.collections(accountId).forEach(collection -> items.add(collectionView(collection)));
        return new QuestionCollectionsVO(items);
    }

    @Override
    @Transactional
    public QuestionCollectionVO createCollection(long accountId, CreateQuestionCollectionDTO request) {
        String name = collectionName(request.name());
        Long parentId = request.parentCollectionId() == null ? null : positiveId(request.parentCollectionId());
        if (parentId != null && !dao.collectionExists(accountId, parentId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        rejectReservedRootName(name, parentId);
        try {
            long id = dao.createCollection(accountId, name, parentId);
            return collectionView(dao.findCollection(accountId, id)
                    .orElseThrow(() -> new IllegalStateException("创建后未读取到题集")));
        } catch (CollectionNameConflictException exception) {
            throw new BusinessException(ErrorCode.COLLECTION_NAME_EXISTS);
        }
    }

    @Override
    @Transactional
    public QuestionCollectionVO renameCollection(long accountId, String collectionId,
            RenameQuestionCollectionDTO request) {
        long id = positiveId(collectionId);
        QuestionCollectionDO existing = dao.findCollection(accountId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String name = collectionName(request.name());
        rejectReservedRootName(name, existing.getParentCollectionId());
        try {
            if (dao.renameCollection(accountId, id, name) != 1) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
        } catch (CollectionNameConflictException exception) {
            throw new BusinessException(ErrorCode.COLLECTION_NAME_EXISTS);
        }
        return collectionView(dao.findCollection(accountId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }

    @Override
    @Transactional
    public QuestionVO moveQuestion(long accountId, String questionId, MoveQuestionDTO request) {
        long id = positiveId(questionId);
        if (dao.findById(accountId, id).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        Long target = QuestionConverter.INBOX_ID.equals(request.collectionId())
                ? null : positiveId(request.collectionId());
        if (target != null && !dao.collectionExists(accountId, target)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (dao.moveQuestion(accountId, id, target) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return QuestionConverter.detail(dao.findById(accountId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }

    private static QuestionCollectionVO collectionView(QuestionCollectionDO collection) {
        Long parent = collection.getParentCollectionId();
        return new QuestionCollectionVO(Long.toString(collection.getCollectionId()),
                collection.getName(), false, collection.getQuestionCount(),
                parent == null ? null : Long.toString(parent));
    }

    private static String collectionName(String raw) {
        if (raw == null || raw.strip().isEmpty() || raw.strip().length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        return raw.strip();
    }

    private static void rejectReservedRootName(String name, Long parentId) {
        if (parentId == null && "待整理".equals(name)) {
            throw new BusinessException(ErrorCode.COLLECTION_NAME_EXISTS);
        }
    }

    private static long positiveId(String value) {
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
