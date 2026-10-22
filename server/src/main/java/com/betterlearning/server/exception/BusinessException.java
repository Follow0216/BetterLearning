package com.betterlearning.server.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.betterlearning.server.enums.ErrorCode;

/**
 * 业务异常，携带对外错误码、消息、可选字段错误与原始 cause。
 *
 * <p>字段错误按插入顺序复制为不可变映射；{@code null} 或空映射统一转为空映射。
 * 异常消息直接作为对外消息使用，不得包含敏感信息。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    private final Map<String, String> fieldErrors;

    /**
     * 使用错误码的默认中文消息创建异常。
     *
     * @param errorCode 业务错误码
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    /**
     * 使用自定义对外消息创建异常。
     *
     * @param errorCode 业务错误码
     * @param message 对外消息
     */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * 使用自定义消息与字段错误创建异常。
     *
     * @param errorCode 业务错误码
     * @param message 对外消息
     * @param fieldErrors 字段错误，可为空
     */
    public BusinessException(ErrorCode errorCode, String message, Map<String, String> fieldErrors) {
        this(errorCode, message, fieldErrors, null);
    }

    /**
     * 使用完整信息并保留原始异常。
     *
     * @param errorCode 业务错误码
     * @param message 对外消息
     * @param fieldErrors 字段错误，可为空
     * @param cause 原始异常，可为空
     */
    public BusinessException(ErrorCode errorCode, String message, Map<String, String> fieldErrors,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.fieldErrors = copy(fieldErrors);
    }

    /**
     * 返回业务错误码。
     *
     * @return 错误码枚举
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 返回不可变的字段错误映射。
     *
     * @return 字段名到错误消息的映射，未提供时为空映射
     */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    private static Map<String, String> copy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
