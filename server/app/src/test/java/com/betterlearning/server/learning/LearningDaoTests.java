package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.mapper.QuestionCollectionMapper;
import com.betterlearning.server.learning.mapper.QuestionMapper;

import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class LearningDaoTests {

    private final QuestionMapper questions = mock(QuestionMapper.class);
    private final LearningDao dao = new LearningDao(questions, mock(QuestionCollectionMapper.class));

    @Test
    void restoresExplicitDatabaseResourceFailure() {
        PersistenceException wrapped = new PersistenceException("hidden mapper detail",
                new DataAccessResourceFailureException("hidden resource detail"));
        when(questions.count(7L, false, null)).thenThrow(wrapped);
        assertThatThrownBy(() -> dao.count(7L, false, null))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("数据库连接不可用").hasCause(wrapped);
    }

    @Test
    void preservesUnknownMappingFailureInsteadOfReturningEmptyOrUnavailable() {
        PersistenceException mapping = new PersistenceException("invalid mapping");
        when(questions.count(7L, false, null)).thenThrow(mapping);
        assertThatThrownBy(() -> dao.count(7L, false, null)).isSameAs(mapping);
    }
}
