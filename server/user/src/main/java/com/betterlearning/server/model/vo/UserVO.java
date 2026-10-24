package com.betterlearning.server.model.vo;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户信息响应。
 *
 * <p>{@code accountId} 使用十进制字符串，避免前端按数字解析时丢失精度；{@code phone}
 * 与 {@code email} 未绑定时为 {@code null}；{@code role} 为 {@code USER} 或
 * {@code ADMIN}。响应不包含口令哈希与令牌版本。{@link #toString()} 对用户名、
 * 手机号与邮箱脱敏。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Schema(name = "UserVO", description = "用户信息；不包含密码哈希或 token_version")
public class UserVO {

    /** 账号 ID 的十进制字符串形式。 */
    @Schema(type = "string", description = "账号 ID 的十进制字符串形式，避免前端精度丢失",
            example = "1")
    private final String accountId;

    /** 规范化后的用户名。 */
    @Schema(description = "规范化后的用户名", example = "alice_01")
    private final String username;

    /** E.164 手机号；未绑定为 {@code null}。 */
    @Schema(types = {"string", "null"},
            description = "E.164 手机号；未绑定时为 null", example = "+8613800138000")
    private final String phone;

    /** 小写邮箱；未绑定为 {@code null}。 */
    @Schema(types = {"string", "null"},
            description = "小写邮箱；未绑定时为 null", example = "alice@example.com")
    private final String email;

    /** 角色标识。 */
    @Schema(description = "角色，仅 USER 或 ADMIN", example = "USER")
    private final String role;

    /** 创建时间。 */
    @Schema(description = "创建时间", example = "2026-10-22T10:00:00Z")
    private final OffsetDateTime createdAt;

    /** 最后更新时间。 */
    @Schema(description = "最后更新时间", example = "2026-10-22T10:00:00Z")
    private final OffsetDateTime updatedAt;

    /**
     * 创建用户信息响应。
     *
     * @param accountId 账号 ID 的十进制字符串
     * @param username 规范化后的用户名
     * @param phone E.164 手机号，可为 {@code null}
     * @param email 小写邮箱，可为 {@code null}
     * @param role 角色标识
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public UserVO(String accountId, String username, String phone, String email, String role,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.accountId = accountId;
        this.username = username;
        this.phone = phone;
        this.email = email;
        this.role = role;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 返回账号 ID。
     *
     * @return 账号 ID
     */
    public String getAccountId() {
        return accountId;
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
     * 返回手机号。
     *
     * @return 手机号
     */
    public String getPhone() {
        return phone;
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
     * 返回角色标识。
     *
     * @return 角色标识
     */
    public String getRole() {
        return role;
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
     * 返回最后更新时间。
     *
     * @return 最后更新时间
     */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 返回仅含公开标识与时间信息的诊断字符串；用户名、手机号与邮箱输出为保护标记。
     */
    @Override
    public String toString() {
        return "UserVO{accountId='" + accountId + '\''
                + ", role='" + role + '\''
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + ", username='[PROTECTED]'"
                + ", phone='[PROTECTED]'"
                + ", email='[PROTECTED]'}";
    }
}
