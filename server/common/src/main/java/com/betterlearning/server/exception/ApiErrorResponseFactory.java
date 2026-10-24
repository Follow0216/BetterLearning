package com.betterlearning.server.exception;

import java.util.Map;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.model.vo.ApiErrorVO;

import org.springframework.stereotype.Component;

/**
 * 标准错误响应工厂。
 *
 * <p>只负责用 {@link ErrorCode} 的对外 5 位 code 与默认消息构造
 * {@code code/message/traceId/fieldErrors} VO；消息为空时回退到错误码默认文案。HTTP 状态由调用方
 * （MVC 异常处理器或 Security 响应器）设置。MVC 与 Spring Security 的错误出口共用本工厂，
 * 保证响应结构一致。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Component
public class ApiErrorResponseFactory {

    /**
     * 使用错误码默认消息创建错误响应。
     *
     * @param traceId 请求 traceId
     * @param errorCode 错误码
     * @return 标准错误响应
     */
    public ApiErrorVO create(String traceId, ErrorCode errorCode) {
        return create(traceId, errorCode, errorCode.getMessage(), null);
    }

    /**
     * 使用指定消息与字段错误创建错误响应。
     *
     * @param traceId 请求 traceId
     * @param errorCode 错误码
     * @param message 对外消息；为空时使用错误码默认消息
     * @param fieldErrors 字段错误，可为空
     * @return 标准错误响应
     */
    public ApiErrorVO create(String traceId, ErrorCode errorCode, String message,
            Map<String, String> fieldErrors) {
        String resolvedMessage = message == null || message.isBlank()
                ? errorCode.getMessage()
                : message;
        return new ApiErrorVO(errorCode.getCode(), resolvedMessage, traceId, fieldErrors);
    }
}
