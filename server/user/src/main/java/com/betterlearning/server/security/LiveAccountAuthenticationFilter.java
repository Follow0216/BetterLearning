package com.betterlearning.server.security;

import java.io.IOException;
import java.util.Optional;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在资源服务器完成 JWT 校验后，用 {@link AccountAuthenticationChecker} 复核账号仍然有效。
 *
 * <p>无 JWT 认证时直接继续过滤链，交由后续授权规则处理。存在 JWT 认证时：缺少或非法的
 * sub／jti／token_version 写 401 并终止；检查器返回空写 401 并终止；会话或数据库依赖故障
 * 写 503 并终止；校验通过则用账号快照替换安全上下文 principal 后继续过滤链。
 * 所有错误出口都会先清空安全上下文，且不会在写完响应后继续链。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class LiveAccountAuthenticationFilter extends OncePerRequestFilter {

    private final AccountAuthenticationChecker accountAuthenticationChecker;

    private final ApiSecurityErrorResponder responder;

    /**
     * 创建认证复核过滤器。
     *
     * @param accountAuthenticationChecker 会话与账号校验器
     * @param responder 安全错误响应器
     */
    public LiveAccountAuthenticationFilter(
            AccountAuthenticationChecker accountAuthenticationChecker,
            ApiSecurityErrorResponder responder) {
        this.accountAuthenticationChecker = accountAuthenticationChecker;
        this.responder = responder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>仅在当前认证为 JWT 时执行账号复核；错误响应写入后立即返回，不调用过滤链。</p>
     *
     * @throws ServletException 后续过滤链抛出
     * @throws IOException 写错误响应或后续过滤链失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            filterChain.doFilter(request, response);
            return;
        }

        Jwt jwt = jwtAuthentication.getToken();
        Long accountId = parseAccountId(jwt.getSubject());
        String jwtId = jwt.getId();
        Integer tokenVersion = parseTokenVersion(jwt);
        if (accountId == null || jwtId == null || tokenVersion == null) {
            rejectUnauthorized(request, response);
            return;
        }

        Optional<AuthenticatedAccount> authenticated;
        try {
            authenticated = accountAuthenticationChecker.authenticate(accountId, jwtId,
                    tokenVersion);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.SERVICE_UNAVAILABLE) {
                throw exception;
            }
            rejectUnavailable(request, response, exception);
            return;
        } catch (DataAccessException | CannotCreateTransactionException exception) {
            rejectUnavailable(request, response, exception);
            return;
        }

        if (authenticated.isEmpty()) {
            rejectUnauthorized(request, response);
            return;
        }

        AccountAuthenticationToken replacement =
                AccountAuthenticationToken.authenticated(authenticated.get());
        replacement.setDetails(jwtAuthentication.getDetails());
        SecurityContextHolder.getContext().setAuthentication(replacement);
        filterChain.doFilter(request, response);
    }

    private void rejectUnauthorized(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        SecurityContextHolder.clearContext();
        responder.unauthorized(request, response, true);
    }

    private void rejectUnavailable(HttpServletRequest request, HttpServletResponse response,
            Throwable cause) throws IOException {
        SecurityContextHolder.clearContext();
        responder.serviceUnavailable(request, response, cause);
    }

    private static Long parseAccountId(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        try {
            long accountId = Long.parseLong(subject);
            return accountId > 0 ? accountId : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer parseTokenVersion(Jwt jwt) {
        Object claim = jwt.getClaims().get(JwtTokenService.TOKEN_VERSION_CLAIM);
        if (!(claim instanceof Number number)) {
            return null;
        }
        long value = number.longValue();
        if (value < 0 || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }
}
