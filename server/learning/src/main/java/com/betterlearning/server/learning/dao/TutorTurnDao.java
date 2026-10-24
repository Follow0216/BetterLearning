package com.betterlearning.server.learning.dao;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.betterlearning.server.learning.mapper.TutorTurnMapper;
import com.betterlearning.server.learning.model.dataobject.TutorTurnDO;

import org.apache.ibatis.exceptions.PersistenceException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Repository;

/** 辅导轮次SQL调用与既有窄化资源故障规则。 */
@Repository
public class TutorTurnDao {
    private final TutorTurnMapper mapper;

    public TutorTurnDao(TutorTurnMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<TutorTurnDO> find(long accountId, long questionId, String turnId) {
        return Optional.ofNullable(execute(() -> mapper.find(accountId, questionId, turnId)));
    }

    public boolean lockAccount(long accountId) {
        return execute(() -> mapper.lockAccount(accountId)) != null;
    }

    public Optional<TutorTurnDO> findRequest(long accountId, String requestId) {
        return Optional.ofNullable(execute(() -> mapper.findRequest(accountId, requestId)));
    }

    public Optional<TutorTurnDO> active(long accountId) {
        return Optional.ofNullable(execute(() -> mapper.active(accountId)));
    }

    public List<TutorTurnDO> recent(long accountId, long questionId) {
        return execute(() -> mapper.recent(accountId, questionId));
    }

    public TutorTurnDO insert(TutorTurnDO turn) {
        return execute(() -> mapper.insert(turn));
    }

    public int bind(long accountId, long questionId, String turnId, String fileRef, String hash) {
        return execute(() -> mapper.bind(accountId, questionId, turnId, fileRef, hash));
    }

    public int claim(long accountId, long questionId, String turnId, String status) {
        return execute(() -> mapper.claim(accountId, questionId, turnId, status));
    }

    public int finish(long accountId, long questionId, String turnId, String fileRef, String status,
            String answer, String failureCode, String failureMessage) {
        return execute(() -> mapper.finish(accountId, questionId, turnId, fileRef, status, answer, failureCode, failureMessage));
    }

    public int cancel(long accountId, long questionId, String turnId) {
        return execute(() -> mapper.cancel(accountId, questionId, turnId));
    }

    private static <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (PersistenceException | MyBatisSystemException exception) {
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Throwable current = exception;
            while (current != null && seen.add(current)) {
                if (current instanceof DataAccessResourceFailureException) {
                    throw new DataAccessResourceFailureException("数据库连接不可用", exception);
                }
                current = current.getCause();
            }
            throw exception;
        }
    }
}
