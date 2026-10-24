package com.betterlearning.server.learning.dao;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.betterlearning.server.learning.exception.CollectionNameConflictException;
import com.betterlearning.server.learning.mapper.QuestionCollectionMapper;
import com.betterlearning.server.learning.mapper.QuestionMapper;
import com.betterlearning.server.learning.model.dataobject.QuestionCollectionDO;
import com.betterlearning.server.learning.model.dataobject.QuestionDO;

import org.apache.ibatis.exceptions.PersistenceException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** 按 owner 与 ID 查询单个题集。 */
    public Optional<QuestionCollectionDO> findCollection(long accountId, long collectionId) {
        return Optional.ofNullable(execute(() -> collectionMapper.findById(accountId, collectionId)));
    }

    /** 创建题集，只翻译既有名称索引冲突。 */
    public long createCollection(long accountId, String name, Long parentId) {
        Long id = execute(() -> collectionMapper.insertReturningId(accountId, name, parentId));
        if (id == null) {
            // 专用 INSERT 仅在同级名称目标索引冲突时 DO NOTHING；未知约束仍抛出。
            throw new CollectionNameConflictException(null);
        }
        return id;
    }

    /** 仅更新名称，不更改题集所属账号或父级。 */
    public int renameCollection(long accountId, long collectionId, String name) {
        try {
            return execute(() -> collectionMapper.rename(accountId, collectionId, name));
        } catch (DataIntegrityViolationException | PersistenceException | MyBatisSystemException exception) {
            throw translateRenameConflict(exception);
        }
    }

    /** 单题归属更新，null 代表待整理。 */
    public int moveQuestion(long accountId, long questionId, Long collectionId) {
        return execute(() -> questionMapper.move(accountId, questionId, collectionId));
    }

    /**
     * 仅用于专用 rename SQL：只改 name，不改主键、账号或父级，23505 只对应同级名称索引。
     * 创建采用目标索引 ON CONFLICT，不把此规则应用于其他 SQL 或全局异常处理。
     */
    private static RuntimeException translateRenameConflict(RuntimeException exception) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
            return new CollectionNameConflictException(exception);
        }
        return exception;
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
