package com.betterlearning.server.dao;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.betterlearning.server.enums.UserUniqueField;
import com.betterlearning.server.exception.UserUniqueConflictException;
import com.betterlearning.server.mapper.UserMapper;
import com.betterlearning.server.model.dataobject.UserDO;

import org.apache.ibatis.exceptions.PersistenceException;
import org.mybatis.spring.MyBatisSystemException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * 用户数据访问。
 *
 * <p>基于 MyBatis-Plus：按主键使用 {@code selectById}，按用户名使用条件构造器，三个写操作
 * 调用 XML 固定语句。查询以 {@link Optional} 表达“记录不存在”，数据库访问失败则抛出
 * {@link org.springframework.dao.DataAccessException}，不得用空结果掩盖故障。</p>
 *
 * <p>插入与联系方式更新捕获完整性异常并翻译 PostgreSQL 唯一约束，未识别时原样抛出。
 * MyBatis 包装的连接故障恢复为 {@link DataAccessResourceFailureException}，保留原始异常作为
 * cause；其他 MyBatis 异常原样返回，避免把 Mapper、映射或编程错误误判为依赖不可用。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Repository
public class UserDao {

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private static final Map<String, UserUniqueField> CONSTRAINT_FIELDS = Map.of(
            "uk_app_user_username", UserUniqueField.USERNAME,
            "uk_app_user_phone", UserUniqueField.PHONE,
            "uk_app_user_email", UserUniqueField.EMAIL);

    private final UserMapper userMapper;

    /**
     * 创建用户数据访问。
     *
     * @param userMapper 用户 Mapper
     */
    public UserDao(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 按用户名查询用户。
     *
     * <p>用户名由数据库唯一约束保证单值；使用条件构造器查询列表后严格判断：0 条返回空，
     * 1 条返回该记录，多条属于内部状态不一致并抛出 {@link IllegalStateException}。</p>
     *
     * @param username 已规范化的用户名
     * @return 用户记录；不存在时为空
     * @throws IllegalStateException 查询返回多条记录
     * @throws org.springframework.dao.DataAccessException 数据库访问失败
     */
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
     * 按账户 ID 查询用户。
     *
     * <p>主键查询无结果时返回空。</p>
     *
     * @param accountId 账户 ID
     * @return 用户记录；不存在时为空
     * @throws org.springframework.dao.DataAccessException 数据库访问失败
     */
    public Optional<UserDO> findByAccountId(long accountId) {
        return Optional.ofNullable(execute(() -> userMapper.selectById(accountId)));
    }

    /**
     * 创建用户。
     *
     * <p>数据库未返回主键属于内部状态不一致，抛出 {@link IllegalStateException}；
     * 完整性异常先交给约束翻译，未识别时原样抛出。</p>
     *
     * @param username 已规范化的用户名
     * @param passwordHash 已编码的密码哈希，不得为明文
     * @return 数据库生成的账户 ID
     * @throws IllegalStateException 数据库未返回账户 ID
     * @throws UserUniqueConflictException 用户名违反唯一约束
     * @throws DataIntegrityViolationException 未识别的完整性约束冲突，原样抛出
     * @throws org.springframework.dao.DataAccessException 其他数据库访问失败
     */
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
     * 更新用户联系方式。
     *
     * <p>只更新调用方声明已提供的字段；为 {@code true} 且值为 {@code null} 时写入 NULL，
     * 实现“解绑”语义。两个字段均未提供时不访问数据库，直接返回 0。</p>
     *
     * @param accountId 账户 ID
     * @param phoneProvided 请求是否提供了手机号字段
     * @param phone 已规范化的手机号，可为空
     * @param emailProvided 请求是否提供了邮箱字段
     * @param email 已规范化的邮箱，可为空
     * @return 受影响行数；0 表示账户不存在或两个字段均未提供
     * @throws UserUniqueConflictException 手机号或邮箱违反唯一约束
     * @throws DataIntegrityViolationException 未识别的完整性约束冲突，原样抛出
     * @throws org.springframework.dao.DataAccessException 其他数据库访问失败
     */
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
     * 以比较并交换方式更新密码。
     *
     * <p>仅当账户 ID、当前令牌版本与旧密码哈希同时匹配时才写入新哈希，并将令牌版本加一。
     * 该方法不区分具体不匹配项，也不在 0 行时抛出异常；影响行数原样返回，由服务层判定为并发冲突。</p>
     *
     * @param accountId 账户 ID
     * @param expectedPasswordHash 期望的当前密码哈希
     * @param expectedTokenVersion 期望的当前令牌版本
     * @param newPasswordHash 新密码哈希
     * @return 1 表示更新成功；0 表示任一条件不匹配、未更新
     * @throws org.springframework.dao.DataAccessException 数据库访问失败
     */
    public int updatePassword(long accountId, String expectedPasswordHash, int expectedTokenVersion,
            String newPasswordHash) {
        return execute(() -> userMapper.updatePassword(accountId, expectedPasswordHash,
                expectedTokenVersion, newPasswordHash));
    }

    private static <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (PersistenceException | MyBatisSystemException exception) {
            throw translateMybatisFailure(exception);
        }
    }

    /**
     * 仅把 cause 链中的连接资源故障转换为 Spring 数据访问资源异常，其他异常原样返回。
     *
     * @param exception MyBatis 抛出的运行时异常
     * @return 连接故障时返回新的 {@link DataAccessResourceFailureException}，否则返回原异常
     */
    private static RuntimeException translateMybatisFailure(RuntimeException exception) {
        if (!containsConnectionFailure(exception)) {
            return exception;
        }
        return new DataAccessResourceFailureException("数据库连接不可用", exception);
    }

    private static boolean containsConnectionFailure(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof DataAccessResourceFailureException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static RuntimeException translateOrOriginal(
            DataIntegrityViolationException exception) {
        return translateConstraint(exception)
                .<RuntimeException>map(conflict -> conflict)
                .orElse(exception);
    }

    /**
     * 仅当 SQLState 为 23505 且服务器返回的约束名与预期完全一致时翻译，不解析异常文本。
     *
     * @param exception 数据完整性异常
     * @return 识别成功时返回对应冲突，否则返回空
     */
    private static Optional<UserUniqueConflictException> translateConstraint(
            DataIntegrityViolationException exception) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        if (!(cause instanceof PSQLException psqlException)
                || !UNIQUE_VIOLATION_SQL_STATE.equals(psqlException.getSQLState())) {
            return Optional.empty();
        }
        ServerErrorMessage serverError = psqlException.getServerErrorMessage();
        if (serverError == null) {
            return Optional.empty();
        }
        String constraint = serverError.getConstraint();
        if (constraint == null) {
            return Optional.empty();
        }
        UserUniqueField field = CONSTRAINT_FIELDS.get(constraint);
        return field == null
                ? Optional.empty()
                : Optional.of(new UserUniqueConflictException(field, exception));
    }
}
