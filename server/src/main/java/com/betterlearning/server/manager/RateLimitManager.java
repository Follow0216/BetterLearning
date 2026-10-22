package com.betterlearning.server.manager;

/**
 * 认证接口限流抽象。
 *
 * <p>注册按客户端 IP 限流；登录同时按客户端 IP 与规范化用户名限流。均采用固定窗口计数，
 * 任一维度超限即拒绝。限流存储不可用时失败关闭，不放行请求。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public interface RateLimitManager {

    /**
     * 检查注册限流。
     *
     * @param clientIp 客户端 IP
     * @throws com.betterlearning.server.exception.BusinessException 超出限制（{@code A0501}）或
     *         限流存储不可用（{@code C0001}）
     */
    void checkRegistration(String clientIp);

    /**
     * 检查登录限流，依次校验 IP 与用户名两个维度。
     *
     * @param clientIp 客户端 IP
     * @param normalizedUsername 已规范化的用户名
     * @throws com.betterlearning.server.exception.BusinessException 任一维度超出限制（{@code A0501}）或
     *         限流存储不可用（{@code C0001}）
     */
    void checkLogin(String clientIp, String normalizedUsername);
}
