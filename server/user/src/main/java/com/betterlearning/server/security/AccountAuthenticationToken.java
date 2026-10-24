package com.betterlearning.server.security;

import java.util.List;
import java.util.Objects;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 已认证账号的安全上下文令牌。
 *
 * <p>principal 为数据库校验后的 {@link AuthenticatedAccount} 快照，权限固定由角色生成
 * {@code ROLE_<role>}。本令牌不携带凭据，{@link #getCredentials()} 恒为 {@code null}。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class AccountAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedAccount principal;

    private AccountAuthenticationToken(AuthenticatedAccount principal) {
        super(authorities(principal));
        this.principal = principal;
        setAuthenticated(true);
    }

    /**
     * 创建已认证令牌。
     *
     * @param principal 认证通过的账号快照
     * @return 已设置认证状态与角色权限的令牌
     * @throws NullPointerException 账号快照或其角色为空
     */
    public static AccountAuthenticationToken authenticated(AuthenticatedAccount principal) {
        return new AccountAuthenticationToken(Objects.requireNonNull(principal, "principal"));
    }

    /**
     * 返回空凭据；认证信息来自已校验的账号快照。
     *
     * @return 恒为 {@code null}
     */
    @Override
    public Object getCredentials() {
        return null;
    }

    /**
     * 返回认证通过的账号快照。
     *
     * @return 账号快照
     */
    @Override
    public AuthenticatedAccount getPrincipal() {
        return principal;
    }

    private static List<GrantedAuthority> authorities(AuthenticatedAccount principal) {
        Objects.requireNonNull(principal, "principal");
        String role = Objects.requireNonNull(principal.role(), "role");
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
