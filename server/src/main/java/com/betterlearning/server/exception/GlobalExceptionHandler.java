package com.betterlearning.server.exception;

import java.util.Map;
import java.util.TreeMap;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.model.vo.ApiErrorVO;
import com.betterlearning.server.security.TraceIdContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * MVC 全局异常处理器。
 *
 * <p>把常见请求、业务与依赖异常转换为标准错误响应；字段错误按字段名排序并保留首个消息。
 * 非业务的依赖异常、未知完整性异常与未处理异常使用固定文案，不回传原异常消息；
 * 业务异常使用调用方提供的对外文案。500 与 503 记录脱敏诊断（异常类型、栈帧与 cause 结构）。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String OPERATION_BUSINESS_UNAVAILABLE = "business_unavailable";

    private static final String OPERATION_DATA_INTEGRITY = "data_integrity_violation";

    private static final String OPERATION_DEPENDENCY_UNAVAILABLE = "dependency_unavailable";

    private static final String OPERATION_UNHANDLED = "unhandled_exception";

    private final ApiErrorResponseFactory errorFactory;

    /**
     * 创建全局异常处理器。
     *
     * @param errorFactory 标准错误响应工厂
     */
    public GlobalExceptionHandler(ApiErrorResponseFactory errorFactory) {
        this.errorFactory = errorFactory;
    }

    /**
     * 处理请求体字段校验失败，返回 400 A0400。
     *
     * @param exception 字段校验异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorVO> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = new TreeMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            String message = fieldError.getDefaultMessage() == null
                    ? "参数不合法"
                    : fieldError.getDefaultMessage();
            fieldErrors.putIfAbsent(fieldError.getField(), message);
        }
        return respond(ErrorCode.INVALID_PARAM, "请求参数不合法", fieldErrors, request);
    }

    /**
     * 处理方法参数校验失败，返回 400 A0400。
     *
     * @param exception 方法参数校验异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorVO> handleHandlerMethodValidation(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = new TreeMap<>();
        exception.getParameterValidationResults().forEach(result -> {
            String parameterName = result.getMethodParameter().getParameterName();
            String field = parameterName == null ? "request" : parameterName;
            result.getResolvableErrors().forEach(error -> {
                String message = error.getDefaultMessage() == null
                        ? "参数不合法"
                        : error.getDefaultMessage();
                fieldErrors.putIfAbsent(field, message);
            });
        });
        return respond(ErrorCode.INVALID_PARAM, "请求参数不合法", fieldErrors, request);
    }

    /**
     * 处理约束违规，返回 400 A0400。
     *
     * @param exception 约束违规异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorVO> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = new TreeMap<>();
        exception.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            String field = path.contains(".")
                    ? path.substring(path.lastIndexOf('.') + 1)
                    : path;
            if (!field.isBlank()) {
                fieldErrors.putIfAbsent(field, violation.getMessage());
            }
        });
        return respond(ErrorCode.INVALID_PARAM, "请求参数不合法", fieldErrors, request);
    }

    /**
     * 处理请求体无法解析，返回 400 A0427。
     *
     * @param exception 消息不可读异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorVO> handleMessageNotReadable(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return respond(ErrorCode.INVALID_JSON, ErrorCode.INVALID_JSON.getMessage(), null, request);
    }

    /**
     * 处理缺失请求参数或参数类型不匹配，返回 400 A0400。
     *
     * @param exception 请求参数异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorVO> handleRequestParameter(Exception exception,
            HttpServletRequest request) {
        return respond(ErrorCode.INVALID_PARAM, "请求参数不合法", null, request);
    }

    /**
     * 处理业务异常，按异常携带的错误码、消息与字段错误返回。
     *
     * <p>仅 {@code SERVICE_UNAVAILABLE} 记录脱敏诊断，其余业务异常不回写日志。</p>
     *
     * @param exception 业务异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorVO> handleBusiness(BusinessException exception,
            HttpServletRequest request) {
        if (exception.getErrorCode() == ErrorCode.SERVICE_UNAVAILABLE) {
            SafeFailureDiagnostics.logError(log, OPERATION_BUSINESS_UNAVAILABLE,
                    TraceIdContext.resolve(request), exception);
        }
        return respond(exception.getErrorCode(), exception.getMessage(),
                exception.getFieldErrors(), request);
    }

    /**
     * 处理未识别的数据完整性错误，记录脱敏诊断并返回 500 B0001。
     *
     * @param exception 数据完整性异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorVO> handleDataIntegrity(DataIntegrityViolationException exception,
            HttpServletRequest request) {
        SafeFailureDiagnostics.logError(log, OPERATION_DATA_INTEGRITY,
                TraceIdContext.resolve(request), exception);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage(), null,
                request);
    }

    /**
     * 处理数据库连接或事务创建失败，记录脱敏诊断并返回 503 C0001。
     *
     * @param exception 依赖不可用异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler({DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class})
    public ResponseEntity<ApiErrorVO> handleDependencyUnavailable(Exception exception,
            HttpServletRequest request) {
        SafeFailureDiagnostics.logError(log, OPERATION_DEPENDENCY_UNAVAILABLE,
                TraceIdContext.resolve(request), exception);
        return respond(ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE.getMessage(),
                null, request);
    }

    /**
     * 处理资源不存在，返回 404 A0606。
     *
     * @param exception 资源未找到异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorVO> handleNoResource(NoResourceFoundException exception,
            HttpServletRequest request) {
        return respond(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage(), null, request);
    }

    /**
     * 处理不支持的请求方法，返回 405 A0507。
     *
     * @param exception 方法不支持异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorVO> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getMessage(),
                null, request);
    }

    /**
     * 处理不支持的媒体类型，返回 415 A0428。
     *
     * @param exception 媒体类型异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorVO> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                ErrorCode.UNSUPPORTED_MEDIA_TYPE.getMessage(), null, request);
    }

    /**
     * 兜底处理未映射异常，记录脱敏诊断并返回 500 B0001。
     *
     * @param exception 未处理异常
     * @param request 当前请求
     * @return 标准错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorVO> handleUnhandled(Exception exception,
            HttpServletRequest request) {
        SafeFailureDiagnostics.logError(log, OPERATION_UNHANDLED,
                TraceIdContext.resolve(request), exception);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage(), null,
                request);
    }

    /**
     * 组装标准错误响应。
     *
     * @param errorCode 错误码
     * @param message 对外消息
     * @param fieldErrors 字段错误，可为空
     * @param request 当前请求，用于解析 traceId
     * @return 带对应 HTTP 状态的错误响应
     */
    private ResponseEntity<ApiErrorVO> respond(ErrorCode errorCode, String message,
            Map<String, String> fieldErrors, HttpServletRequest request) {
        ApiErrorVO body = errorFactory.create(TraceIdContext.resolve(request), errorCode, message,
                fieldErrors);
        return ResponseEntity.status(errorCode.getStatus()).body(body);
    }

}
