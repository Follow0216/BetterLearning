package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.exception.CollectionNameConflictException;
import com.betterlearning.server.learning.mapper.QuestionCollectionMapper;
import com.betterlearning.server.learning.mapper.QuestionMapper;

import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

class LearningDaoTests {

    private final QuestionMapper questions = mock(QuestionMapper.class);
    private final QuestionCollectionMapper collections = mock(QuestionCollectionMapper.class);
    private final LearningDao dao = new LearningDao(questions, collections);

    @Test
    void collectionInsertOnlyTreatsTargetConflictNullAsNameConflict() {
        when(collections.insertReturningId(7L, "name", null)).thenReturn(null);
        assertThatThrownBy(() -> dao.createCollection(7L, "name", null))
                .isInstanceOf(CollectionNameConflictException.class);
    }

    @Test
    void renameOnlyMapsDuplicateSqlStateAndPreservesOtherConstraintFailures() {
        when(collections.rename(7L, 1L, "name")).thenThrow(new DataIntegrityViolationException(
                "hidden duplicate", new SQLException("hidden", "23505")));
        assertThatThrownBy(() -> dao.renameCollection(7L, 1L, "name"))
                .isInstanceOf(CollectionNameConflictException.class);
        DataIntegrityViolationException foreign = new DataIntegrityViolationException(
                "hidden foreign key", new SQLException("hidden", "23503"));
        when(collections.rename(7L, 1L, "name")).thenThrow(foreign);
        assertThatThrownBy(() -> dao.renameCollection(7L, 1L, "name")).isSameAs(foreign);
    }

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
