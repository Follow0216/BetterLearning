package com.betterlearning.server.security;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 资源服务器的 JWT 声明校验器。
 *
 * <p>签名与算法校验由 {@code JwtDecoder} 完成，本类只校验声明：iss 精确匹配配置、aud 为单值
 * 或集合且包含配置值、sub 为正整数账号、jti 为 UUID、token_version 为非负整数、iat 不晚于
 * 当前时间、exp 晚于当前时间；nbf 可缺省，存在时不得晚于当前时间。校验失败返回
 * {@code invalid_token} 错误；校验过程中出现运行时异常时折叠为单条 {@code invalid_token} 失败。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class StrictJwtClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final String INVALID_TOKEN = "invalid_token";

    private static final Pattern SUBJECT_PATTERN = Pattern.compile("^[1-9][0-9]*$");

    private static final Pattern JWT_ID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final String issuer;

    private final String audience;

    private final Clock clock;

    /**
     * 创建声明校验器。
     *
     * @param issuer 期望的令牌签发者
     * @param audience 期望的受众
     * @param clock 用于校验 iat、exp 与 nbf 的时钟
     */
    public StrictJwtClaimsValidator(String issuer, String audience, Clock clock) {
        this.issuer = issuer;
        this.audience = audience;
        this.clock = clock;
    }

    /**
     * 校验已解码令牌的全部约定声明。
     *
     * @param jwt 已解码令牌
     * @return 全部声明合法时成功；否则返回包含各条错误说明的失败结果
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            List<OAuth2Error> errors = new ArrayList<>();
            Map<String, Object> claims = jwt.getClaims();
            validateIssuer(claims, errors);
            validateAudience(claims, errors);
            validateSubject(claims, errors);
            validateJwtId(claims, errors);
            validateTokenVersion(claims, errors);
            validateTimes(claims, errors);
            validateNotBefore(claims, errors);
            return errors.isEmpty()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(errors);
        } catch (RuntimeException exception) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(INVALID_TOKEN, "JWT 声明不合法", null));
        }
    }

    private void validateIssuer(Map<String, Object> claims, List<OAuth2Error> errors) {
        Object value = claims.get("iss");
        if (!(value instanceof String issuerValue) || !issuer.equals(issuerValue)) {
            errors.add(error("JWT issuer 不匹配"));
        }
    }

    private void validateAudience(Map<String, Object> claims, List<OAuth2Error> errors) {
        Object value = claims.get("aud");
        if (value instanceof String singleAudience) {
            if (!audience.equals(singleAudience)) {
                errors.add(error("JWT audience 不匹配"));
            }
            return;
        }
        if (value instanceof Collection<?> audiences) {
            if (!audiences.contains(audience)) {
                errors.add(error("JWT audience 不匹配"));
            }
            return;
        }
        errors.add(error("JWT audience 不匹配"));
    }

    private void validateSubject(Map<String, Object> claims, List<OAuth2Error> errors) {
        Object value = claims.get("sub");
        if (!(value instanceof String subject) || !SUBJECT_PATTERN.matcher(subject).matches()) {
            errors.add(error("JWT subject 必须为正整数账号"));
            return;
        }
        try {
            if (Long.parseLong(subject) <= 0) {
                errors.add(error("JWT subject 必须为正整数账号"));
            }
        } catch (NumberFormatException exception) {
            errors.add(error("JWT subject 必须为正整数账号"));
        }
    }

    private void validateJwtId(Map<String, Object> claims, List<OAuth2Error> errors) {
        Object value = claims.get("jti");
        if (!(value instanceof String jwtId) || !JWT_ID_PATTERN.matcher(jwtId).matches()) {
            errors.add(error("JWT jti 必须为 UUID"));
        }
    }

    private void validateTokenVersion(Map<String, Object> claims, List<OAuth2Error> errors) {
        Object value = claims.get(JwtTokenService.TOKEN_VERSION_CLAIM);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            errors.add(error("JWT token_version 必须为非负整数"));
            return;
        }
        long tokenVersion = ((Number) value).longValue();
        if (tokenVersion < 0 || tokenVersion > Integer.MAX_VALUE) {
            errors.add(error("JWT token_version 必须为非负整数"));
        }
    }

    private void validateTimes(Map<String, Object> claims, List<OAuth2Error> errors) {
        Instant now = clock.instant();
        Instant issuedAt = toInstant(claims.get("iat"));
        if (issuedAt == null) {
            errors.add(error("JWT iat 缺失或类型非法"));
        } else if (issuedAt.isAfter(now)) {
            errors.add(error("JWT iat 不能晚于当前时间"));
        }
        Instant expiresAt = toInstant(claims.get("exp"));
        if (expiresAt == null) {
            errors.add(error("JWT exp 缺失或类型非法"));
        } else if (!expiresAt.isAfter(now)) {
            errors.add(error("JWT 已过期"));
        }
    }

    private void validateNotBefore(Map<String, Object> claims, List<OAuth2Error> errors) {
        if (!claims.containsKey("nbf")) {
            return;
        }
        Instant notBefore = toInstant(claims.get("nbf"));
        if (notBefore == null) {
            errors.add(error("JWT nbf 类型非法"));
            return;
        }
        if (notBefore.isAfter(clock.instant())) {
            errors.add(error("JWT nbf 不能晚于当前时间"));
        }
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Number number) {
            long seconds = number.longValue();
            if (number.doubleValue() != (double) seconds) {
                return null;
            }
            return Instant.ofEpochSecond(seconds);
        }
        return null;
    }

    private static OAuth2Error error(String description) {
        return new OAuth2Error(INVALID_TOKEN, description, null);
    }
}
