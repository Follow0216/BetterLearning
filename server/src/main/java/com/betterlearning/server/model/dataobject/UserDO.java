package com.betterlearning.server.model.dataobject;

import java.time.OffsetDateTime;

/**
 * 账号数据库行对象。
 *
 * <p>对应账号表的一行，包含口令哈希与令牌版本等内部字段，只在持久化与业务内部传递，
 * 不直接作为响应返回。{@code accountId} 与 {@code tokenVersion} 在数据库中非空，
 * 映射缺失时不补默认值；其余字段可能为 {@code null}，由业务显式处理。</p>
 *
 * <p>{@link #toString()} 对用户名、手机号、邮箱与口令哈希统一脱敏。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public class UserDO {

    /** 数据库主键；未赋值或映射缺失时为 {@code null}，业务读取后必须校验非空。 */
    private Long accountId;

    /** 规范化后的登录用户名。 */
    private String username;

    /** BCrypt 口令哈希；敏感值，仅用于认证，不得写入日志或响应。 */
    private String passwordHash;

    /** E.164 手机号；未绑定时为 {@code null}。 */
    private String phone;

    /** 小写邮箱；未绑定时为 {@code null}。 */
    private String email;

    /** 角色标识，如 {@code USER} 或 {@code ADMIN}。 */
    private String role;

    /** 令牌版本；数据库非空，映射层不得把缺失值静默转为 0。 */
    private Integer tokenVersion;

    /** 创建时间。 */
    private OffsetDateTime createdAt;

    /** 最后更新时间。 */
    private OffsetDateTime updatedAt;

    /**
     * 返回账号主键。
     *
     * @return 账号主键
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 设置账号主键。
     *
     * @param accountId 账号主键
     */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    /**
     * 返回用户名。
     *
     * @return 用户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * 设置用户名。
     *
     * @param username 用户名
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * 返回口令哈希。
     *
     * @return 口令哈希
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * 设置口令哈希。
     *
     * @param passwordHash 口令哈希
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * 返回手机号。
     *
     * @return 手机号
     */
    public String getPhone() {
        return phone;
    }

    /**
     * 设置手机号。
     *
     * @param phone 手机号
     */
    public void setPhone(String phone) {
        this.phone = phone;
    }

    /**
     * 返回邮箱。
     *
     * @return 邮箱
     */
    public String getEmail() {
        return email;
    }

    /**
     * 设置邮箱。
     *
     * @param email 邮箱
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * 返回角色标识。
     *
     * @return 角色标识
     */
    public String getRole() {
        return role;
    }

    /**
     * 设置角色标识。
     *
     * @param role 角色标识
     */
    public void setRole(String role) {
        this.role = role;
    }

    /**
     * 返回令牌版本。
     *
     * @return 令牌版本
     */
    public Integer getTokenVersion() {
        return tokenVersion;
    }

    /**
     * 设置令牌版本。
     *
     * @param tokenVersion 令牌版本
     */
    public void setTokenVersion(Integer tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    /**
     * 返回创建时间。
     *
     * @return 创建时间
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 设置创建时间。
     *
     * @param createdAt 创建时间
     */
    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 返回最后更新时间。
     *
     * @return 最后更新时间
     */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 设置最后更新时间。
     *
     * @param updatedAt 最后更新时间
     */
    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 返回仅含非敏感元数据的诊断字符串。
     *
     * <p>用户名、手机号、邮箱与密码哈希统一输出为固定保护标记，避免日志泄露。</p>
     */
    @Override
    public String toString() {
        return "UserDO{accountId=" + accountId
                + ", role='" + role + '\''
                + ", tokenVersion=" + tokenVersion
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + ", username='[PROTECTED]'"
                + ", passwordHash='[PROTECTED]'"
                + ", phone='[PROTECTED]'"
                + ", email='[PROTECTED]'}";
    }
}
