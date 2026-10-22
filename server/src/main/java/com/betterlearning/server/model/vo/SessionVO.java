package com.betterlearning.server.model.vo;

import java.util.Objects;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录成功响应。
 *
 * <p>包含 JWT 访问令牌、有效期秒数与用户信息。有效期来自配置（默认 7200 秒），
 * 令牌不提供刷新或自动续期，到期后需重新登录。访问令牌只在此响应中出现，
 * {@link #toString()} 对令牌与用户信息脱敏。构造器只校验 {@code expiresIn} 非空。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Schema(name = "SessionVO", description = "登录成功的令牌与用户信息")
public final class SessionVO {

    /** 令牌类型固定值。 */
    private static final String TOKEN_TYPE = "Bearer";

    /** 访问令牌；敏感值，仅登录成功时返回。 */
    @Schema(description = "HS256 JWT 访问令牌，仅在登录成功时返回；请放入 Authorization: Bearer 头",
            example = "eyJhbGciOiJIUzI1NiJ9...")
    private final String accessToken;

    /** 令牌有效期，单位为秒。 */
    @Schema(description = "有效期（秒），默认 7200；令牌不支持刷新或自动续期",
            example = "7200")
    private final Long expiresIn;

    /** 登录账号信息。 */
    private final UserVO user;

    /**
     * 创建登录成功响应。
     *
     * @param accessToken JWT 访问令牌
     * @param expiresIn 有效期秒数，不能为空
     * @param user 登录账号信息
     */
    public SessionVO(String accessToken, Long expiresIn, UserVO user) {
        this.accessToken = accessToken;
        this.expiresIn = Objects.requireNonNull(expiresIn, "expiresIn 不能为空");
        this.user = user;
    }

    /**
     * 返回访问令牌。
     *
     * @return 访问令牌
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * 返回固定为 {@code Bearer} 的令牌类型。
     *
     * @return 固定为 {@code Bearer} 的令牌类型
     */
    @Schema(description = "令牌类型，固定为 Bearer", example = "Bearer")
    public String getTokenType() {
        return TOKEN_TYPE;
    }

    /**
     * 返回有效期秒数。
     *
     * @return 有效期秒数
     */
    public Long getExpiresIn() {
        return expiresIn;
    }

    /**
     * 返回登录账号信息。
     *
     * @return 登录账号信息
     */
    public UserVO getUser() {
        return user;
    }

    /**
     * 返回不含令牌、用户名或其他用户隐私的诊断字符串。
     */
    @Override
    public String toString() {
        return "SessionVO{tokenType='" + TOKEN_TYPE + "', expiresIn=" + expiresIn
                + ", user='[PROTECTED]', accessToken='[PROTECTED]'}";
    }
}
