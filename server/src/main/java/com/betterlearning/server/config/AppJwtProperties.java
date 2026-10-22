package com.betterlearning.server.config;

import java.time.Duration;
import java.util.Base64;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 签发与校验配置。
 *
 * <p>绑定 {@code app.jwt} 前缀。密钥、签发者与受众没有内置默认值，必须由环境变量提供；
 * 密钥须为合法 Base64 且解码后不少于 32 字节，令牌有效期默认为 2 小时且必须为正时长。
 * 令牌不续期，到期后需重新登录。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public class AppJwtProperties {

    /** 密钥解码后的最小字节数，对应 HS256 对密钥强度的要求。 */
    private static final int MIN_SECRET_BYTES = 32;

    /** Base64 编码的 HMAC 密钥，无默认值。 */
    @NotBlank(message = "app.jwt.secret-base64 不能为空")
    private String secretBase64;

    /** 令牌签发者标识，无默认值。 */
    @NotBlank(message = "app.jwt.issuer 不能为空")
    private String issuer;

    /** 令牌受众标识，无默认值。 */
    @NotBlank(message = "app.jwt.audience 不能为空")
    private String audience;

    /** 访问令牌有效期，默认 2 小时。 */
    @NotNull(message = "app.jwt.access-token-ttl 不能为空")
    private Duration accessTokenTtl = Duration.ofHours(2);

    /**
     * 校验密钥的编码格式与长度。
     *
     * <p>空值由 {@link NotBlank} 负责校验，此处直接视为通过；非空时要求 Base64 可解码且
     * 解码后不少于 32 字节。</p>
     *
     * @return 密钥为空、或合法且长度足够时为 {@code true}
     */
    @AssertTrue(message = "app.jwt.secret-base64 必须是合法 Base64 且解码后至少 32 字节")
    public boolean isSecretBase64Valid() {
        if (secretBase64 == null || secretBase64.isBlank()) {
            return true;
        }
        try {
            return Base64.getDecoder().decode(secretBase64).length >= MIN_SECRET_BYTES;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 校验访问令牌有效期为非零正时长。
     *
     * @return 有效期非空且大于零时为 {@code true}
     */
    @AssertTrue(message = "app.jwt.access-token-ttl 必须为正数时长")
    public boolean isAccessTokenTtlPositive() {
        return accessTokenTtl != null && !accessTokenTtl.isZero() && !accessTokenTtl.isNegative();
    }

    /** 返回 Base64 编码的密钥。 */
    public String getSecretBase64() {
        return secretBase64;
    }

    /** 设置 Base64 编码的密钥。 */
    public void setSecretBase64(String secretBase64) {
        this.secretBase64 = secretBase64;
    }

    /** 返回令牌签发者。 */
    public String getIssuer() {
        return issuer;
    }

    /** 设置令牌签发者。 */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /** 返回令牌受众。 */
    public String getAudience() {
        return audience;
    }

    /** 设置令牌受众。 */
    public void setAudience(String audience) {
        this.audience = audience;
    }

    /** 返回访问令牌有效期。 */
    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    /** 设置访问令牌有效期。 */
    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }
}
