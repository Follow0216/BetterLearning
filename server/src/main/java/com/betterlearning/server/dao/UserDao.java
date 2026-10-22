package com.betterlearning.server.dao;

import java.util.Optional;

import com.betterlearning.server.exception.UserUniqueConflictException;
import com.betterlearning.server.model.dataobject.UserDO;

/**
 * 用户数据访问接口。
 *
 * <p>查询以 {@link Optional} 表达“记录不存在”，数据库访问失败则抛出
 * {@link org.springframework.dao.DataAccessException}，不得用空结果掩盖故障。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public interface UserDao {

    /**
     * 按用户名查询用户。
     *
     * @param username 已规范化的用户名
     * @return 用户记录；不存在时为空
     * @throws org.springframework.dao.DataAccessException 数据库访问失败
     */
    Optional<UserDO> findByUsername(String username);

    /**
     * 按账户 ID 查询用户。
     *
     * @param accountId 账户 ID
     * @return 用户记录；不存在时为空
     * @throws org.springframework.dao.DataAccessException 数据库访问失败
     */
    Optional<UserDO> findByAccountId(long accountId);

    /**
     * 创建用户。
     *
     * @param username 已规范化的用户名
     * @param passwordHash 已编码的密码哈希，不得为明文
     * @return 数据库生成的账户 ID
     * @throws UserUniqueConflictException 用户名违反唯一约束
     * @throws org.springframework.dao.DataIntegrityViolationException 未识别的完整性约束冲突，原样抛出
     * @throws org.springframework.dao.DataAccessException 其他数据库访问失败
     */
    long insert(String username, String passwordHash);

    /**
     * 更新用户联系方式。
     *
     * <p>只更新调用方声明已提供的字段；为 {@code true} 且值为 {@code null} 时写入 NULL，
     * 实现“解绑”语义。</p>
     *
     * @param accountId 账户 ID
     * @param phoneProvided 请求是否提供了手机号字段
     * @param phone 已规范化的手机号，可为空
     * @param emailProvided 请求是否提供了邮箱字段
     * @param email 已规范化的邮箱，可为空
     * @return 受影响行数；0 表示账户不存在或两个字段均未提供
     * @throws UserUniqueConflictException 手机号或邮箱违反唯一约束
     * @throws org.springframework.dao.DataIntegrityViolationException 未识别的完整性约束冲突，原样抛出
     * @throws org.springframework.dao.DataAccessException 其他数据库访问失败
     */
    int updateContactInfo(long accountId, boolean phoneProvided, String phone,
            boolean emailProvided, String email);

    /**
     * 以比较并交换方式更新密码。
     *
     * <p>仅当账户 ID、当前令牌版本与旧密码哈希同时匹配时才写入新哈希，并将令牌版本加一。
     * 该方法不区分具体不匹配项，也不在 0 行时抛出异常。</p>
     *
     * @param accountId 账户 ID
     * @param expectedPasswordHash 期望的当前密码哈希
     * @param expectedTokenVersion 期望的当前令牌版本
     * @param newPasswordHash 新密码哈希
     * @return 1 表示更新成功；0 表示任一条件不匹配、未更新
     * @throws org.springframework.dao.DataAccessException 数据库访问失败
     */
    int updatePassword(long accountId, String expectedPasswordHash, int expectedTokenVersion,
            String newPasswordHash);
}
