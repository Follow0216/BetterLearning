package com.betterlearning.server.manager;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.security.IssuedToken;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的会话存储实现。
 *
 * <p>键为 {@code auth:session:<jti>}，值为账户 ID 的十进制字符串。写入使用 {@code SET ... PXAT}
 * 指定绝对到期毫秒，读取与后续认证均不续期。Redis 访问异常统一转换为
 * {@link BusinessException}（{@code C0001}），不会被当作会话缺失处理。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Component
public class RedisSessionManager implements SessionManager {

    private static final String SESSION_KEY_PREFIX = "auth:session:";

    private static final byte[] PXAT = "PXAT".getBytes(StandardCharsets.UTF_8);

    private final StringRedisTemplate redisTemplate;

    private final Clock clock;

    /**
     * 创建会话存储实现。
     *
     * @param redisTemplate Redis 字符串模板
     * @param clock 用于判断令牌是否已过期的时钟
     */
    public RedisSessionManager(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>写入前校验 jti、账户 ID 与到期时间；到期时间必须严格晚于当前时钟时间。</p>
     */
    @Override
    public void create(IssuedToken issuedToken, long accountId) {
        if (issuedToken == null || issuedToken.getJwtId() == null
                || issuedToken.getJwtId().isBlank()) {
            throw new IllegalArgumentException("会话令牌缺少 jti");
        }
        if (accountId <= 0) {
            throw new IllegalArgumentException("accountId 必须为正整数");
        }
        if (issuedToken.getExpiresAt() == null
                || !issuedToken.getExpiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("令牌已过期，不能创建会话");
        }
        writeSession(issuedToken.getJwtId(), accountId, issuedToken.getExpiresAt());
    }

    /**
     * {@inheritDoc}
     *
     * <p>只比较会话值是否等于账户 ID；键不存在或值不匹配均返回 {@code false}。
     * Redis 访问失败转换为 {@code SERVICE_UNAVAILABLE} 抛出。</p>
     */
    @Override
    public boolean isActive(String jwtId, long accountId) {
        if (jwtId == null || jwtId.isBlank() || accountId <= 0) {
            return false;
        }
        try {
            String value = redisTemplate.opsForValue().get(key(jwtId));
            return Long.toString(accountId).equals(value);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>空 jti 不访问 Redis；删除不存在的键按成功处理。</p>
     */
    @Override
    public void delete(String jwtId) {
        if (jwtId == null || jwtId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(key(jwtId));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void writeSession(String jwtId, long accountId, Instant expiresAt) {
        byte[] key = key(jwtId).getBytes(StandardCharsets.UTF_8);
        byte[] value = Long.toString(accountId).getBytes(StandardCharsets.UTF_8);
        byte[] absoluteExpiry = Long.toString(expiresAt.toEpochMilli())
                .getBytes(StandardCharsets.UTF_8);
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.execute("SET", key, value, PXAT, absoluteExpiry);
                return null;
            });
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private static String key(String jwtId) {
        return SESSION_KEY_PREFIX + jwtId;
    }

    private static BusinessException unavailable(DataAccessException exception) {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                ErrorCode.SERVICE_UNAVAILABLE.getMessage(), null, exception);
    }
}
