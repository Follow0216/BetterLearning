package com.betterlearning.server.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;

import com.betterlearning.server.mapper.UserMapper;
import com.betterlearning.server.model.dataobject.UserDO;

import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * 验证 {@link UserDao} 的查询、写入、连接故障与完整性异常边界。
 *
 * <p>全部用例通过 mock {@link UserMapper} 驱动 DAO 公开方法，不依赖数据库。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
class UserDaoTests {

    private UserMapper userMapper;

    private UserDao userDao;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userDao = new UserDao(userMapper);
    }

    @Test
    void translatesConnectionFailureFromUsernameLookup() {
        PersistenceException failure = connectionFailure();
        when(userMapper.selectList(any())).thenThrow(failure);

        assertThatThrownBy(() -> userDao.findByUsername("alice"))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCause(failure);
    }

    @Test
    void returnsEmptyWhenUsernameLookupHasNoRows() {
        when(userMapper.selectList(any())).thenReturn(List.of());

        assertThat(userDao.findByUsername("alice")).isEmpty();
    }

    @Test
    void returnsSingleRowWhenUsernameLookupHasOneRow() {
        UserDO user = new UserDO();
        when(userMapper.selectList(any())).thenReturn(List.of(user));

        assertThat(userDao.findByUsername("alice")).containsSame(user);
    }

    @Test
    void rejectsMultipleRowsForUsernameLookup() {
        when(userMapper.selectList(any())).thenReturn(List.of(new UserDO(), new UserDO()));

        assertThatThrownBy(() -> userDao.findByUsername("alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("用户名查询返回多条记录");
    }

    @Test
    void translatesMybatisSystemFailureFromAccountLookup() {
        MyBatisSystemException failure =
                new MyBatisSystemException("query failed", connectionCause());
        when(userMapper.selectById(anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> userDao.findByAccountId(1L))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCause(failure);
    }

    @Test
    void translatesConnectionFailureFromInsert() {
        PersistenceException failure = connectionFailure();
        when(userMapper.insertReturningAccountId(anyString(), anyString())).thenThrow(failure);

        assertThatThrownBy(() -> userDao.insert("alice", "hash"))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCause(failure);
    }

    @Test
    void translatesConnectionFailureFromContactUpdate() {
        PersistenceException failure = connectionFailure();
        when(userMapper.updateContactInfo(anyLong(), anyBoolean(), any(), anyBoolean(), any()))
                .thenThrow(failure);

        assertThatThrownBy(() -> userDao.updateContactInfo(1L, true, "13800138000", false, null))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCause(failure);
    }

    @Test
    void translatesConnectionFailureFromPasswordUpdate() {
        PersistenceException failure = connectionFailure();
        when(userMapper.updatePassword(anyLong(), anyString(), anyInt(), anyString()))
                .thenThrow(failure);

        assertThatThrownBy(() -> userDao.updatePassword(1L, "old-hash", 0, "new-hash"))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCause(failure);
    }

    @Test
    void keepsNonConnectionPersistenceFailure() {
        PersistenceException failure =
                new PersistenceException("mapping failed", new IllegalArgumentException("bad mapper"));
        when(userMapper.selectList(any())).thenThrow(failure);

        assertThatThrownBy(() -> userDao.findByUsername("alice")).isSameAs(failure);
    }

    @Test
    void keepsUnknownIntegrityFailureForExistingTranslationBoundary() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unknown integrity violation");
        when(userMapper.insertReturningAccountId(anyString(), anyString())).thenThrow(failure);

        assertThatThrownBy(() -> userDao.insert("alice", "hash")).isSameAs(failure);
    }

    @Test
    void translatesPersistenceExceptionWrappingConnectionFailure() {
        CannotGetJdbcConnectionException connectionFailure = connectionCause();
        PersistenceException mybatisFailure =
                new PersistenceException("query failed", connectionFailure);
        when(userMapper.selectList(any())).thenThrow(mybatisFailure);

        Throwable translated = catchThrowable(() -> userDao.findByUsername("alice"));

        assertThat(translated).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(translated).hasCause(mybatisFailure);
        assertThat(translated.getCause().getCause()).isSameAs(connectionFailure);
    }

    @Test
    void translatesNestedConnectionFailure() {
        CannotGetJdbcConnectionException connectionFailure = connectionCause();
        PersistenceException mybatisFailure = new PersistenceException("query failed",
                new IllegalStateException("wrapper", connectionFailure));
        when(userMapper.selectList(any())).thenThrow(mybatisFailure);

        Throwable translated = catchThrowable(() -> userDao.findByUsername("alice"));

        assertThat(translated).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(translated).hasCause(mybatisFailure);
        assertThat(translated.getCause().getCause().getCause()).isSameAs(connectionFailure);
    }

    @Test
    void translatesMybatisSystemExceptionWrappingConnectionFailure() {
        CannotGetJdbcConnectionException connectionFailure = connectionCause();
        MyBatisSystemException mybatisFailure =
                new MyBatisSystemException("query failed", connectionFailure);
        when(userMapper.selectById(anyLong())).thenThrow(mybatisFailure);

        Throwable translated = catchThrowable(() -> userDao.findByAccountId(1L));

        assertThat(translated).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(translated).hasCause(mybatisFailure);
    }

    @Test
    void keepsPersistenceExceptionWithoutConnectionFailure() {
        PersistenceException mybatisFailure =
                new PersistenceException("mapping failed", new IllegalArgumentException("bad mapper"));
        when(userMapper.selectList(any())).thenThrow(mybatisFailure);

        assertThatThrownBy(() -> userDao.findByUsername("alice")).isSameAs(mybatisFailure);
    }

    @Test
    void keepsIntegrityViolationAsOriginal() {
        DataIntegrityViolationException integrityFailure =
                new DataIntegrityViolationException("duplicate key");
        when(userMapper.insertReturningAccountId(anyString(), anyString()))
                .thenThrow(integrityFailure);

        assertThatThrownBy(() -> userDao.insert("alice", "hash")).isSameAs(integrityFailure);
    }

    private static PersistenceException connectionFailure() {
        return new PersistenceException("query failed", connectionCause());
    }

    private static CannotGetJdbcConnectionException connectionCause() {
        return new CannotGetJdbcConnectionException("failed", new SQLException("refused"));
    }
}
