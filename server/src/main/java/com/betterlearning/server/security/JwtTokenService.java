package com.betterlearning.server.security;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.betterlearning.server.config.AppJwtProperties;

import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * JWT 签发服务。
 *
 * <p>使用 {@link JwtEncoder} 以 HS256 签发，有效期取自 {@code AppJwtProperties.accessTokenTtl}
 * （默认两小时），签发后不再刷新。声明包含 iss、aud、sub（账号 ID）、iat、exp、jti 与
 * {@value #TOKEN_VERSION_CLAIM}。本类只负责签发，不负责解码、会话校验或账号状态检查。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Service
public class JwtTokenService {

    /** 令牌版本声明名。 */
    public static final String TOKEN_VERSION_CLAIM = "token_version";

    private final JwtEncoder jwtEncoder;

    private final AppJwtProperties jwtProperties;

    private final Clock clock;

    /**
     * 创建令牌签发服务。
     *
     * @param jwtEncoder JWT 编码器
     * @param jwtProperties JWT 配置，提供 issuer、audience 与有效期
     * @param clock 签发时间与到期时间的时钟
     */
    public JwtTokenService(JwtEncoder jwtEncoder, AppJwtProperties jwtProperties,
            Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    /**
     * 为指定账号签发令牌。
     *
     * <p>签发时间按秒截断，到期时间等于签发时间加配置有效期；每次签发生成新的随机 jti。</p>
     *
     * @param accountId 账户 ID，必须为正数
     * @param tokenVersion 当前令牌版本，不能为负数
     * @return 令牌串、jti 与签发／到期时间
     * @throws IllegalArgumentException 账户 ID 非正或令牌版本为负
     */
    public IssuedToken issue(long accountId, int tokenVersion) {
        if (accountId <= 0) {
            throw new IllegalArgumentException("accountId 必须为正整数");
        }
        if (tokenVersion < 0) {
            throw new IllegalArgumentException("token_version 不能为负数");
        }
        Instant issuedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(jwtProperties.getAccessTokenTtl());
        String jwtId = UUID.randomUUID().toString();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(Long.toString(accountId))
                .audience(List.of(jwtProperties.getAudience()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(jwtId)
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new IssuedToken(token, jwtId, issuedAt, expiresAt);
    }
}
