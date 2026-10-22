package com.betterlearning.server.manager;

import com.betterlearning.server.security.IssuedToken;

/**
 * 会话存储抽象。
 *
 * <p>会话在创建时写入绝对到期时间，读取时不续期。会话缺失与存储依赖故障必须区分：
 * 前者返回 {@code false}，后者抛出异常，避免故障时误放行或误拒绝。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public interface SessionManager {

    /**
     * 创建会话记录，到期时间与令牌一致。
     *
     * @param issuedToken 已签发的令牌，需包含 jti 与到期时间
     * @param accountId 账户 ID，必须为正数
     * @throws IllegalArgumentException 缺少 jti、账户 ID 非正或令牌已过期
     * @throws com.betterlearning.server.exception.BusinessException 会话存储不可用（{@code C0001}）
     */
    void create(IssuedToken issuedToken, long accountId);

    /**
     * 判断会话是否仍有效且属于指定账户。
     *
     * @param jwtId 令牌 jti
     * @param accountId 账户 ID
     * @return 会话存在且账户匹配时为 {@code true}；参数非法、会话缺失或账户不匹配时为 {@code false}
     * @throws com.betterlearning.server.exception.BusinessException 会话存储不可用（{@code C0001}），
     *         不得降级为 {@code false}
     */
    boolean isActive(String jwtId, long accountId);

    /**
     * 删除指定会话。
     *
     * <p>jti 为空时直接返回；删除不存在的会话不视为错误。</p>
     *
     * @param jwtId 令牌 jti
     * @throws com.betterlearning.server.exception.BusinessException 会话存储不可用（{@code C0001}）
     */
    void delete(String jwtId);
}
