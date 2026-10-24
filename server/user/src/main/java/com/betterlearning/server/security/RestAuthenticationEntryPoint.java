package com.betterlearning.server.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 将未认证请求写为标准 401 JSON。
 *
 * <p>请求带有 Authorization 头时视为令牌无效，返回 {@code invalid_token} 挑战；否则返回
 * 普通 {@code Bearer} 挑战。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiSecurityErrorResponder responder;

    /**
     * 创建未认证入口处理器。
     *
     * @param responder 安全错误响应器
     */
    public RestAuthenticationEntryPoint(ApiSecurityErrorResponder responder) {
        this.responder = responder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>根据是否携带 Authorization 头选择 401 挑战类型，响应体为标准错误 JSON。</p>
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        boolean invalidToken = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        responder.unauthorized(request, response, invalidToken);
    }
}
