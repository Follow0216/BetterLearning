package com.betterlearning.server.security;

import java.time.Instant;

/**
 * 已签发令牌的值对象，包含令牌串、jti 与签发／到期时间。
 *
 * <p>{@link #toString()} 不输出令牌串与 jti。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class IssuedToken {

    private final String token;

    private final String jwtId;

    private final Instant issuedAt;

    private final Instant expiresAt;

    /**
     * 创建令牌值对象。
     *
     * @param token 已编码的 JWT
     * @param jwtId 令牌标识 jti
     * @param issuedAt 签发时间
     * @param expiresAt 到期时间
     */
    public IssuedToken(String token, String jwtId, Instant issuedAt, Instant expiresAt) {
        this.token = token;
        this.jwtId = jwtId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 返回已编码的 JWT。
     *
     * @return JWT 字符串
     */
    public String getToken() {
        return token;
    }

    /**
     * 返回令牌标识 jti。
     *
     * @return jti
     */
    public String getJwtId() {
        return jwtId;
    }

    /**
     * 返回签发时间。
     *
     * @return 签发时间
     */
    public Instant getIssuedAt() {
        return issuedAt;
    }

    /**
     * 返回到期时间。
     *
     * @return 到期时间
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * 返回不含令牌值与 jti 的诊断字符串。
     */
    @Override
    public String toString() {
        return "IssuedToken{jwtId='[PROTECTED]', issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt + ", token='[PROTECTED]'}";
    }
}
