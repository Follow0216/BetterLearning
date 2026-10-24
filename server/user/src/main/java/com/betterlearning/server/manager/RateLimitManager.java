package com.betterlearning.server.manager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import com.betterlearning.server.config.AppAuthProperties;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 固定窗口计数的认证接口限流。
 *
 * <p>注册按客户端 IP 限流；登录同时按客户端 IP 与规范化用户名限流，任一维度超限即拒绝。
 * 通过 Lua 脚本原子执行 {@code INCR}，首次计数时设置窗口过期；计数超过阈值即拒绝。
 * Redis 键由维度前缀与 SHA-256 摘要组成，不直接暴露 IP 或用户名。Redis 访问异常转换为
 * {@link BusinessException}（{@code C0001}），限流失败关闭，不放行请求。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Component
public class RateLimitManager {

    private static final String REGISTRATION_IP_PREFIX = "rate:registration:ip:";

    private static final String LOGIN_IP_PREFIX = "rate:login:ip:";

    private static final String LOGIN_USERNAME_PREFIX = "rate:login:username:";

    private static final RedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1])\n"
            + "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end\n"
            + "if current > tonumber(ARGV[2]) then return 0 end\n"
            + "return 1",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    private final AppAuthProperties authProperties;

    /**
     * 创建限流实现。
     *
     * @param redisTemplate Redis 字符串模板
     * @param authProperties 注册与登录的限额及窗口配置
     */
    public RateLimitManager(StringRedisTemplate redisTemplate,
            AppAuthProperties authProperties) {
        this.redisTemplate = redisTemplate;
        this.authProperties = authProperties;
    }

    /**
     * 检查注册限流。
     *
     * <p>仅使用注册配置中的 IP 维度与窗口。</p>
     *
     * @param clientIp 客户端 IP
     * @throws BusinessException 超出限制（{@code A0501}）或限流存储不可用（{@code C0001}）
     */
    public void checkRegistration(String clientIp) {
        AppAuthProperties.Registration registration = authProperties.getRegistration();
        check(REGISTRATION_IP_PREFIX, subject(clientIp), registration.getIpLimit(),
                registration.getWindow());
    }

    /**
     * 检查登录限流，依次校验 IP 与用户名两个维度。
     *
     * <p>先检查 IP 维度并在超限时立即拒绝，再检查用户名维度；两个维度使用同一登录窗口配置。</p>
     *
     * @param clientIp 客户端 IP
     * @param normalizedUsername 已规范化的用户名
     * @throws BusinessException 任一维度超出限制（{@code A0501}）或限流存储不可用（{@code C0001}）
     */
    public void checkLogin(String clientIp, String normalizedUsername) {
        AppAuthProperties.Login login = authProperties.getLogin();
        check(LOGIN_IP_PREFIX, subject(clientIp), login.getIpLimit(), login.getWindow());
        check(LOGIN_USERNAME_PREFIX, subject(normalizedUsername), login.getUsernameLimit(),
                login.getWindow());
    }

    private void check(String dimensionPrefix, String subject, int limit, Duration window) {
        long windowMillis = windowMillis(window);
        String key = dimensionPrefix + digest(subject);
        Long allowed;
        try {
            allowed = redisTemplate.execute(FIXED_WINDOW_SCRIPT, List.of(key),
                    Long.toString(windowMillis), Integer.toString(limit));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
        if (allowed == null || allowed.longValue() != 1L) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
    }

    private static String subject(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static long windowMillis(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("限流窗口必须为正数");
        }
        try {
            long millis = window.toMillis();
            if (millis < 1) {
                throw new IllegalArgumentException("限流窗口至少为1毫秒");
            }
            return millis;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("限流窗口超出可表示范围", exception);
        }
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static BusinessException unavailable(DataAccessException exception) {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                ErrorCode.SERVICE_UNAVAILABLE.getMessage(), null, exception);
    }
}
