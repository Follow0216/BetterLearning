package com.betterlearning.server.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个请求生成并传播 traceId 的最高优先级过滤器。
 *
 * <p>不读取或信任客户端传入的 {@code X-Request-Id}，而是为每个请求生成新值，写入 MDC、
 * 请求属性与响应头；请求结束时在 {@code finally} 中恢复上一 MDC 值或移除键，避免线程复用
 * 导致串值。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /**
     * {@inheritDoc}
     *
     * <p>生成 traceId 后先写请求属性、MDC 与响应头，再继续过滤链；无论成功或异常都恢复 MDC。</p>
     *
     * @throws ServletException 后续过滤链抛出
     * @throws IOException 后续过滤链抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceIdContext.newTraceId();
        String previousTraceId = MDC.get(TraceIdContext.MDC_KEY);
        TraceIdContext.bind(traceId);
        request.setAttribute(TraceIdContext.REQUEST_ATTRIBUTE, traceId);
        response.setHeader(TraceIdContext.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceIdContext.restore(previousTraceId);
        }
    }
}
