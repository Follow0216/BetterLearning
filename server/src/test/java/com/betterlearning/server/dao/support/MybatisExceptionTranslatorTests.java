package com.betterlearning.server.dao.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class MybatisExceptionTranslatorTests {

    @Test
    void translatesPersistenceExceptionWrappingConnectionFailure() {
        CannotGetJdbcConnectionException connectionFailure =
                new CannotGetJdbcConnectionException("failed", new SQLException("refused"));
        PersistenceException mybatisFailure =
                new PersistenceException("query failed", connectionFailure);

        RuntimeException translated = MybatisExceptionTranslator.translate(mybatisFailure);

        assertThat(translated).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(translated).hasCause(mybatisFailure);
        assertThat(translated.getCause().getCause()).isSameAs(connectionFailure);
    }

    @Test
    void translatesNestedConnectionFailure() {
        CannotGetJdbcConnectionException connectionFailure =
                new CannotGetJdbcConnectionException("failed", new SQLException("refused"));
        PersistenceException mybatisFailure = new PersistenceException("query failed",
                new IllegalStateException("wrapper", connectionFailure));

        RuntimeException translated = MybatisExceptionTranslator.translate(mybatisFailure);

        assertThat(translated).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(translated).hasCause(mybatisFailure);
        assertThat(translated.getCause().getCause().getCause()).isSameAs(connectionFailure);
    }

    @Test
    void translatesMybatisSystemExceptionWrappingConnectionFailure() {
        CannotGetJdbcConnectionException connectionFailure =
                new CannotGetJdbcConnectionException("failed", new SQLException("refused"));
        MyBatisSystemException mybatisFailure =
                new MyBatisSystemException("query failed", connectionFailure);

        RuntimeException translated = MybatisExceptionTranslator.translate(mybatisFailure);

        assertThat(translated).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(translated).hasCause(mybatisFailure);
    }

    @Test
    void keepsPersistenceExceptionWithoutConnectionFailure() {
        PersistenceException mybatisFailure =
                new PersistenceException("mapping failed", new IllegalArgumentException("bad mapper"));

        assertThat(MybatisExceptionTranslator.translate(mybatisFailure)).isSameAs(mybatisFailure);
    }

    @Test
    void keepsIntegrityViolationAsOriginal() {
        DataIntegrityViolationException integrityFailure =
                new DataIntegrityViolationException("duplicate key");

        assertThat(MybatisExceptionTranslator.translate(integrityFailure))
                .isSameAs(integrityFailure);
    }
}
