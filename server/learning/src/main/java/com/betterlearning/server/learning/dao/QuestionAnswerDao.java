package com.betterlearning.server.learning.dao;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.betterlearning.server.learning.mapper.QuestionAnswerMapper;
import com.betterlearning.server.learning.model.dataobject.QuestionAnswerDO;

import org.apache.ibatis.exceptions.PersistenceException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Repository;

/** 当前作答访问，保留既有资源故障窄化规则。 */
@Repository
public class QuestionAnswerDao {
    private final QuestionAnswerMapper mapper;

    public QuestionAnswerDao(QuestionAnswerMapper mapper) {
        this.mapper = mapper;
    }

    public boolean lockQuestion(long accountId, long questionId) {
        return execute(() -> mapper.lockQuestion(accountId, questionId)) != null;
    }

    public Optional<QuestionAnswerDO> find(long accountId, long questionId) {
        return Optional.ofNullable(execute(() -> mapper.find(accountId, questionId)));
    }

    public QuestionAnswerDO publish(QuestionAnswerDO answer) {
        return execute(() -> mapper.publish(answer));
    }

    public int updateState(long accountId, long questionId, String fileRef, String state) {
        return execute(() -> mapper.updateState(accountId, questionId, fileRef, state));
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
