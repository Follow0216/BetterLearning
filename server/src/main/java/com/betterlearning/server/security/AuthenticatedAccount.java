package com.betterlearning.server.security;

/**
 * 认证通过后的账号快照；字段由校验器保证非空。
 *
 * <p>角色为读取时的数据库值，不来自令牌声明；快照仅用于本次请求。</p>
 *
 * @param accountId 账户 ID
 * @param username 用户名
 * @param role 数据库中的角色
 * @param tokenVersion 校验通过的令牌版本
 * @param jwtId 当前令牌标识
 * @author BetterLearning
 * @since 2026-10-22
 */
public record AuthenticatedAccount(long accountId, String username, String role,
        int tokenVersion, String jwtId) {

    /**
     * 返回不含用户名与 jti 的诊断字符串。
     */
    @Override
    public String toString() {
        return "AuthenticatedAccount{accountId=" + accountId
                + ", role='" + role + '\''
                + ", tokenVersion=" + tokenVersion
                + ", username='[PROTECTED]'"
                + ", jwtId='[PROTECTED]'}";
    }
}
