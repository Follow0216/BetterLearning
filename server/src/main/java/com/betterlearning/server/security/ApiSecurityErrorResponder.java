package com.betterlearning.server.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.ApiErrorResponseFactory;
import com.betterlearning.server.exception.SafeFailureDiagnostics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 安全过滤链上的 JSON 错误响应器。
 *
 * <p>使用 {@link ApiErrorResponseFactory} 生成标准错误体，traceId 取自请求属性或 MDC。
 * Servlet 写路径在响应已提交时跳过写入；CORS 的 {@link ServerHttpResponse} 路径不做已提交检查。
 * 401 会设置 {@code WWW-Authenticate}；503 先记录脱敏诊断再写响应。
 * 写入后不再继续过滤链，由调用方终止请求。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Component
public class ApiSecurityErrorResponder {

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityErrorResponder.class);

    private static final String BEARER_CHALLENGE = "Bearer";

    private static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    private static final String OPERATION_DEPENDENCY_UNAVAILABLE = "security_dependency_unavailable";

    private final ApiErrorResponseFactory errorFactory;

    private final JsonMapper jsonMapper;

    /**
     * 创建安全错误响应器。
     *
     * @param errorFactory 标准错误响应工厂
     * @param jsonMapper JSON 序列化器
     */
    public ApiSecurityErrorResponder(ApiErrorResponseFactory errorFactory, JsonMapper jsonMapper) {
        this.errorFactory = errorFactory;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 写入 401 未认证响应。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param invalidToken 是否为非法令牌；为 {@code true} 时附带 {@code invalid_token} 挑战
     * @throws IOException 写响应失败
     */
    public void unauthorized(HttpServletRequest request, HttpServletResponse response,
            boolean invalidToken) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                invalidToken ? INVALID_TOKEN_CHALLENGE : BEARER_CHALLENGE);
        write(request, response, ErrorCode.UNAUTHORIZED);
    }

    /**
     * 写入 403 无权限响应。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @throws IOException 写响应失败
     */
    public void forbidden(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        write(request, response, ErrorCode.FORBIDDEN);
    }

    /**
     * 在 CORS 拒绝场景写入 403 响应。
     *
     * <p>该出口拿不到 Servlet 请求，traceId 由调用方从 MDC 取得；响应头显式写入 traceId。</p>
     *
     * @param response 服务端 HTTP 响应
     * @param traceId 当前请求 traceId
     * @throws IOException 写响应失败
     */
    public void forbidden(ServerHttpResponse response, String traceId) throws IOException {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE,
                MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.getHeaders().set(TraceIdContext.HEADER, traceId);
        response.getBody().write(jsonMapper.writeValueAsBytes(
                errorFactory.create(traceId, ErrorCode.FORBIDDEN)));
    }

    /**
     * 记录依赖故障诊断并写入 503 响应。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param cause 触发故障的异常，仅用于脱敏诊断
     * @throws IOException 写响应失败
     */
    public void serviceUnavailable(HttpServletRequest request, HttpServletResponse response,
            Throwable cause) throws IOException {
        SafeFailureDiagnostics.logError(log, OPERATION_DEPENDENCY_UNAVAILABLE,
                TraceIdContext.resolve(request), cause);
        write(request, response, ErrorCode.SERVICE_UNAVAILABLE);
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
            ErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String traceId = TraceIdContext.resolve(request);
        byte[] body = jsonMapper.writeValueAsBytes(
                errorFactory.create(traceId, errorCode));
        response.setStatus(errorCode.getStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(body);
    }
}
