package com.betterlearning.server.dao.impl;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.dao.mapper.UserMapper;
import com.betterlearning.server.dao.support.MybatisExceptionTranslator;
import com.betterlearning.server.dao.support.UserConstraintTranslator;
import com.betterlearning.server.model.dataobject.UserDO;

import org.apache.ibatis.exceptions.PersistenceException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis-Plus 的 {@link UserDao} 实现。
 *
 * <p>按主键使用 {@code selectById}，按用户名使用条件构造器；三个写操作调用 XML 固定语句。
 * 插入与联系方式更新捕获完整性异常并交给 {@link UserConstraintTranslator}，未识别时原样抛出。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Repository
public class MybatisUserDao implements UserDao {

    private final UserMapper userMapper;

    /**
     * 创建 MyBatis 用户数据访问实现。
     *
     * @param userMapper 用户 Mapper
     */
    public MybatisUserDao(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>用户名由数据库唯一约束保证单值；使用条件构造器查询列表后严格判断：0 条返回空，
     * 1 条返回该记录，多条属于内部状态不一致并抛出 {@link IllegalStateException}。</p>
     */
    @Override
    public Optional<UserDO> findByUsername(String username) {
        LambdaQueryWrapper<UserDO> query = new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, username);
        List<UserDO> users = execute(() -> userMapper.selectList(query));
        if (users.isEmpty()) {
            return Optional.empty();
        }
        if (users.size() > 1) {
            throw new IllegalStateException("用户名查询返回多条记录");
        }
        return Optional.of(users.get(0));
    }

    /**
     * {@inheritDoc}
     *
     * <p>主键查询无结果时返回空。</p>
     */
    @Override
    public Optional<UserDO> findByAccountId(long accountId) {
        return Optional.ofNullable(execute(() -> userMapper.selectById(accountId)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>数据库未返回主键属于内部状态不一致，抛出 {@link IllegalStateException}；
     * 完整性异常先交给约束翻译器，未识别时原样抛出。</p>
     */
    @Override
    public long insert(String username, String passwordHash) {
        try {
            Long accountId = execute(
                    () -> userMapper.insertReturningAccountId(username, passwordHash));
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
     * <p>两个字段均未提供时不访问数据库，直接返回 0；否则执行单条更新。</p>
     */
    @Override
    public int updateContactInfo(long accountId, boolean phoneProvided, String phone,
            boolean emailProvided, String email) {
        if (!phoneProvided && !emailProvided) {
            return 0;
        }
        try {
            return execute(() -> userMapper.updateContactInfo(accountId, phoneProvided, phone,
                    emailProvided, email));
        } catch (DataIntegrityViolationException exception) {
            throw translateOrOriginal(exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>影响行数原样返回；0 由服务层判定为并发冲突。</p>
     */
    @Override
    public int updatePassword(long accountId, String expectedPasswordHash, int expectedTokenVersion,
            String newPasswordHash) {
        return execute(() -> userMapper.updatePassword(accountId, expectedPasswordHash,
                expectedTokenVersion, newPasswordHash));
    }

    private static <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (PersistenceException | MyBatisSystemException exception) {
            throw MybatisExceptionTranslator.translate(exception);
        }
    }

    private static RuntimeException translateOrOriginal(
            DataIntegrityViolationException exception) {
        return UserConstraintTranslator.translate(exception)
                .<RuntimeException>map(conflict -> conflict)
                .orElse(exception);
    }
}
