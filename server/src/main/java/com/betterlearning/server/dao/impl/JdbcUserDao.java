package com.betterlearning.server.dao.impl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.dao.support.UserConstraintTranslator;

import com.betterlearning.server.model.dataobject.UserDO;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 基于 {@link JdbcTemplate} 的 {@link UserDao} 实现。
 *
 * <p>查询按列显式映射，包装类型缺失时保持 {@code null}。插入与联系方式更新捕获
 * {@link DataIntegrityViolationException}，先交给 {@link UserConstraintTranslator} 识别唯一约束；
 * 未识别的异常原样抛出。密码更新使用单条比较并交换语句。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Repository
public class JdbcUserDao implements UserDao {

    private static final String SELECT_COLUMNS =
            "account_id, username, password_hash, phone, email, role, token_version, created_at, updated_at";

    private static final String FIND_BY_USERNAME_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM app_user WHERE username = ?";

    private static final String FIND_BY_ACCOUNT_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM app_user WHERE account_id = ?";

    private static final String INSERT_SQL =
            "INSERT INTO app_user (username, password_hash) VALUES (?, ?) RETURNING account_id";

    private static final String UPDATE_PASSWORD_SQL =
            "UPDATE app_user SET password_hash = ?, token_version = token_version + 1, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE account_id = ? AND token_version = ? AND password_hash = ?";

    private static final RowMapper<UserDO> USER_ROW_MAPPER = (resultSet, rowNumber) -> {
        UserDO user = new UserDO();
        user.setAccountId(resultSet.getObject("account_id", Long.class));
        user.setUsername(resultSet.getString("username"));
        user.setPasswordHash(resultSet.getString("password_hash"));
        user.setPhone(resultSet.getString("phone"));
        user.setEmail(resultSet.getString("email"));
        user.setRole(resultSet.getString("role"));
        user.setTokenVersion(resultSet.getObject("token_version", Integer.class));
        user.setCreatedAt(resultSet.getObject("created_at", OffsetDateTime.class));
        user.setUpdatedAt(resultSet.getObject("updated_at", OffsetDateTime.class));
        return user;
    };

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建 JDBC 用户数据访问实现。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public JdbcUserDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>使用参数化查询按用户名取单条记录；用户名由数据库唯一约束保证单值，此处仍只取首行。
     * 查询失败不捕获，由 Spring 转换为 {@link org.springframework.dao.DataAccessException}。</p>
     */
    @Override
    public Optional<UserDO> findByUsername(String username) {
        List<UserDO> users = jdbcTemplate.query(FIND_BY_USERNAME_SQL, USER_ROW_MAPPER, username);
        return users.stream().findFirst();
    }

    /**
     * {@inheritDoc}
     *
     * <p>使用参数化查询按账户 ID 取单条记录；账户 ID 为主键，此处仍只取首行。
     * 查询失败不捕获。</p>
     */
    @Override
    public Optional<UserDO> findByAccountId(long accountId) {
        List<UserDO> users = jdbcTemplate.query(FIND_BY_ACCOUNT_ID_SQL, USER_ROW_MAPPER, accountId);
        return users.stream().findFirst();
    }

    /**
     * {@inheritDoc}
     *
     * <p>使用 {@code INSERT ... RETURNING account_id} 取回生成主键；数据库未返回主键属于内部
     * 状态不一致，抛出 {@link IllegalStateException}。完整性异常先交给约束翻译器，未识别时原样抛出。</p>
     */
    @Override
    public long insert(String username, String passwordHash) {
        try {
            Long accountId = jdbcTemplate.queryForObject(INSERT_SQL, Long.class, username,
                    passwordHash);
            if (accountId == null) {
                throw new IllegalStateException("创建用户后未返回 account_id");
            }
            return accountId;
        } catch (DataIntegrityViolationException exception) {
            throw translateOrOriginal(exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>按提供标志动态拼接 SET 子句，仅更新声明的字段并刷新 {@code updated_at}；
     * 两个字段都未提供时不访问数据库，直接返回 0。完整性异常先交给约束翻译器，未识别时原样抛出。</p>
     */
    @Override
    public int updateContactInfo(long accountId, boolean phoneProvided, String phone,
            boolean emailProvided, String email) {
        if (!phoneProvided && !emailProvided) {
            return 0;
        }

        StringBuilder sql = new StringBuilder("UPDATE app_user SET updated_at = CURRENT_TIMESTAMP");
        List<Object> parameters = new ArrayList<>(3);
        if (phoneProvided) {
            sql.append(", phone = ?");
            parameters.add(phone);
        }
        if (emailProvided) {
            sql.append(", email = ?");
            parameters.add(email);
        }
        sql.append(" WHERE account_id = ?");
        parameters.add(accountId);

        try {
            return jdbcTemplate.update(sql.toString(), parameters.toArray());
        } catch (DataIntegrityViolationException exception) {
            throw translateOrOriginal(exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>单条 UPDATE 同时匹配账户 ID、令牌版本与旧密码哈希，成功时把令牌版本加一并刷新
     * {@code updated_at}；返回 0 不抛异常，由服务层判定并发冲突。</p>
     */
    @Override
    public int updatePassword(long accountId, String expectedPasswordHash, int expectedTokenVersion,
            String newPasswordHash) {
        return jdbcTemplate.update(UPDATE_PASSWORD_SQL, newPasswordHash, accountId,
                expectedTokenVersion, expectedPasswordHash);
    }

    private static RuntimeException translateOrOriginal(
            DataIntegrityViolationException exception) {
        return UserConstraintTranslator.translate(exception)
                .<RuntimeException>map(conflict -> conflict)
                .orElse(exception);
    }
}
