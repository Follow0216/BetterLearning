package com.betterlearning.server.security;

import java.io.IOException;

import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * 将 CORS 拒绝统一写为标准 403 JSON 的处理器。
 *
 * <p>覆盖父类默认的空响应行为；写入后请求即被拒绝，不进入后续过滤链。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public class JsonCorsProcessor extends DefaultCorsProcessor {

    private final ApiSecurityErrorResponder responder;

    /**
     * 创建 CORS 处理器。
     *
     * @param responder 安全错误响应器
     */
    public JsonCorsProcessor(ApiSecurityErrorResponder responder) {
        this.responder = responder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>使用 MDC 中的 traceId 写入 403 标准错误体。</p>
     */
    @Override
    protected void rejectRequest(ServerHttpResponse response) throws IOException {
        responder.forbidden(response, TraceIdContext.currentTraceId());
    }
}
