package com.betterlearning.server.support;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试数据准备工具。
 *
 * <p>只包含各测试类完全一致的清库与请求体构造；账号、密码等测试数据仍由各测试类自行持有。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class TestData {

    private TestData() {
    }

    /**
     * 清空用户表并重置自增，同时清空 Redis 当前库。
     *
     * @param jdbcTemplate 数据库操作模板
     * @param redisTemplate Redis 操作模板
     */
    public static void clean(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        jdbcTemplate.update("TRUNCATE TABLE app_user RESTART IDENTITY");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    /**
     * 构造注册请求体。
     *
     * @param username 用户名
     * @param password 密码
     * @return JSON 请求体
     */
    public static String registerJson(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password
                + "\",\"confirmPassword\":\"" + password + "\"}";
    }

    /**
     * 构造登录请求体。
     *
     * @param username 用户名
     * @param password 密码
     * @return JSON 请求体
     */
    public static String loginJson(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }
}
