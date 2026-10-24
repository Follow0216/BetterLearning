package com.betterlearning.server.learning.dao;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.betterlearning.server.learning.mapper.QuestionCollectionMapper;
import com.betterlearning.server.learning.mapper.QuestionMapper;
import com.betterlearning.server.learning.model.dataobject.QuestionCollectionDO;
import com.betterlearning.server.learning.model.dataobject.QuestionDO;

import org.apache.ibatis.exceptions.PersistenceException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Repository;

/** 学习存储访问；仅恢复明确的连接资源故障，不把未知约束或映射错误当去重。 */
@Repository
public class LearningDao {

    private final QuestionMapper questionMapper;
    private final QuestionCollectionMapper collectionMapper;

    public LearningDao(QuestionMapper questionMapper, QuestionCollectionMapper collectionMapper) {
        this.questionMapper = questionMapper;
        this.collectionMapper = collectionMapper;
    }

    public Long insertIfAbsent(QuestionDO question) {
        return execute(() -> questionMapper.insertIfAbsent(question));
    }

    public Optional<QuestionDO> findByRequestId(long accountId, String requestId) {
        return Optional.ofNullable(execute(() -> questionMapper.findByRequestId(accountId, requestId)));
    }

    public Optional<QuestionDO> findById(long accountId, long questionId) {
        return Optional.ofNullable(execute(() -> questionMapper.findById(accountId, questionId)));
    }

    public List<QuestionDO> findPage(long accountId, boolean filtered, Long collectionId,
            long offset, int limit) {
        return execute(() -> questionMapper.findPage(accountId, filtered, collectionId, offset, limit));
    }

    public long count(long accountId, boolean filtered, Long collectionId) {
        return execute(() -> questionMapper.count(accountId, filtered, collectionId));
    }

    public List<QuestionCollectionDO> collections(long accountId) {
        return execute(() -> collectionMapper.findAll(accountId));
    }

    public boolean collectionExists(long accountId, long collectionId) {
        return execute(() -> collectionMapper.exists(accountId, collectionId));
    }

    private static <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (PersistenceException | MyBatisSystemException exception) {
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            Throwable current = exception;
            while (current != null && visited.add(current)) {
                if (current instanceof DataAccessResourceFailureException) {
                    throw new DataAccessResourceFailureException("数据库连接不可用", exception);
                }
                current = current.getCause();
            }
            throw exception;
        }
    }
}
