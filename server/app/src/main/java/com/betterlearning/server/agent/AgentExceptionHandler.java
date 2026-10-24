package com.betterlearning.server.agent;

import com.betterlearning.server.agent.model.AgentModelException;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.ApiErrorResponseFactory;
import com.betterlearning.server.model.vo.ApiErrorVO;
import com.betterlearning.server.security.TraceIdContext;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 模型接入错误只输出固定安全文案；不透传供应商响应或配置。 */
@RestControllerAdvice
@Order(-1)
public class AgentExceptionHandler {
    private final ApiErrorResponseFactory errors;

    public AgentExceptionHandler(ApiErrorResponseFactory errors) {
        this.errors = errors;
    }

    @ExceptionHandler(AgentModelException.class)
    public ResponseEntity<ApiErrorVO> modelUnavailable(AgentModelException exception, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.MODEL_UNAVAILABLE.getStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(errors.create(TraceIdContext.resolve(request), ErrorCode.MODEL_UNAVAILABLE,
                exception.failure().message(), null));
    }
}
