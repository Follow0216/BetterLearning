package com.betterlearning.server.security;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;

/**
 * 请求 traceId 的读取、生成与 MDC 绑定工具。
 *
 * <p>traceId 以请求属性为首选来源，其次读取 MDC，均不存在时生成新值。响应头使用
 * {@value #HEADER}，MDC 键为 {@value #MDC_KEY}。绑定与恢复仅供过滤器在请求线程内使用。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class TraceIdContext {

    /** 响应头中的 traceId 名称。 */
    public static final String HEADER = "X-Request-Id";

    /** MDC 中的 traceId 键。 */
    public static final String MDC_KEY = "traceId";

    /** 请求属性中的 traceId 键。 */
    public static final String REQUEST_ATTRIBUTE = TraceIdContext.class.getName() + ".traceId";

    /**
     * 工具类，禁止实例化。
     */
    private TraceIdContext() {
    }

    /**
     * 按请求属性、MDC、新生成值的顺序解析 traceId。
     *
     * @param request 当前请求
     * @return 现有或新生成的 traceId
     */
    public static String resolve(HttpServletRequest request) {
        Object attribute = request.getAttribute(REQUEST_ATTRIBUTE);
        if (attribute instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        String fromMdc = MDC.get(MDC_KEY);
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return newTraceId();
    }

    /**
     * 读取当前线程 MDC 中的 traceId。
     *
     * @return MDC 中的 traceId；不存在时生成新值
     */
    public static String currentTraceId() {
        String fromMdc = MDC.get(MDC_KEY);
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return newTraceId();
    }

    /**
     * 生成新的 traceId。
     *
     * @return 去掉连字符的 UUID 字符串
     */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 将 traceId 绑定到当前线程 MDC。
     *
     * @param traceId 待绑定的 traceId
     */
    static void bind(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    /**
     * 恢复请求开始前的 MDC 值。
     *
     * @param previousTraceId 之前的 traceId；为 {@code null} 时移除 MDC 键
     */
    static void restore(String previousTraceId) {
        if (previousTraceId == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, previousTraceId);
        }
    }
}
