package com.betterlearning.server.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 将已认证但权限不足的请求写为标准 403 JSON。
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiSecurityErrorResponder responder;

    /**
     * 创建无权限处理器。
     *
     * @param responder 安全错误响应器
     */
    public RestAccessDeniedHandler(ApiSecurityErrorResponder responder) {
        this.responder = responder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>使用标准错误体写入 403，不输出异常细节。</p>
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        responder.forbidden(request, response);
    }
}
