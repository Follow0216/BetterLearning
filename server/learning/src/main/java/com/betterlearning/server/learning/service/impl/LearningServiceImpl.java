package com.betterlearning.server.learning.service.impl;

import java.util.ArrayList;
import java.util.List;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.converter.QuestionConverter;
import com.betterlearning.server.learning.dao.LearningDao;
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

/** 当前账号题库服务；事务内原子去重，确认后不提供编辑或整理能力。 */
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
        dao.collections(accountId).forEach(collection -> items.add(new QuestionCollectionVO(
                Long.toString(collection.getCollectionId()), collection.getName(), false,
                collection.getQuestionCount())));
        return new QuestionCollectionsVO(items);
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
