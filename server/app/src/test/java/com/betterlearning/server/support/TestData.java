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
        cleanDatabase(jdbcTemplate);
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    public static void cleanDatabase(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("TRUNCATE TABLE ma_index_stage, ma_legacy_generation_recovery, ma_file_gc, ma_source_deletion, ma_query_embedding, ma_note_request, ma_note_version, ma_retrieval_chunk, ma_index_job, ma_source_operation, ma_source_cursor, ma_cursor, ma_citation, ma_result, ma_evidence, ma_attempt, ma_step, ma_retry, ma_run, ma_request, "
                + "ma_anchor, ma_source_version, ma_source, ma_outbox, tutor_turn, question_answer, "
                + "question, question_collection, app_user RESTART IDENTITY");
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
