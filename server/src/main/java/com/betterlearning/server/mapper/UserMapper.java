package com.betterlearning.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.betterlearning.server.model.dataobject.UserDO;

import org.apache.ibatis.annotations.Param;

/**
 * 用户表 Mapper。
 *
 * <p>继承 {@link BaseMapper} 提供按主键查询；用户名查询由 DAO 使用条件构造器完成。
 * 三个写操作使用 XML 固定 SQL，避免通用 CRUD 无法表达的 RETURNING 与比较并交换语义。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public interface UserMapper extends BaseMapper<UserDO> {

    /**
     * 插入用户名与口令哈希，并返回数据库生成的账户 ID。
     *
     * @param username 已规范化的用户名
     * @param passwordHash 已编码的口令哈希
     * @return 生成的账户 ID；数据库未返回时为 {@code null}
     */
    Long insertReturningAccountId(@Param("username") String username,
            @Param("passwordHash") String passwordHash);

    /**
     * 按提供标志更新联系方式。
     *
     * <p>只更新调用方声明已提供的字段；提供且值为 {@code null} 时写入 NULL。两个标志均为
     * {@code false} 时仍会生成仅刷新 {@code updated_at} 的语句，由 DAO 在调用前拦截。</p>
     *
     * @param accountId 账户 ID
     * @param phoneProvided 是否提供手机号字段
     * @param phone 手机号，可为空
     * @param emailProvided 是否提供邮箱字段
     * @param email 邮箱，可为空
     * @return 受影响行数
     */
    int updateContactInfo(@Param("accountId") long accountId,
            @Param("phoneProvided") boolean phoneProvided,
            @Param("phone") String phone,
            @Param("emailProvided") boolean emailProvided,
            @Param("email") String email);

    /**
     * 以比较并交换方式更新口令哈希与令牌版本。
     *
     * @param accountId 账户 ID
     * @param expectedPasswordHash 期望的当前口令哈希
     * @param expectedTokenVersion 期望的当前令牌版本
     * @param newPasswordHash 新口令哈希
     * @return 1 表示更新成功；0 表示任一条件不匹配
     */
    int updatePassword(@Param("accountId") long accountId,
            @Param("expectedPasswordHash") String expectedPasswordHash,
            @Param("expectedTokenVersion") int expectedTokenVersion,
            @Param("newPasswordHash") String newPasswordHash);
}
