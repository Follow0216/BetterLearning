package com.betterlearning.server.learning.controller;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.ApiErrorResponseFactory;
import com.betterlearning.server.learning.service.NoteService.NoteFailure;
import com.betterlearning.server.model.vo.ApiErrorVO;
import com.betterlearning.server.security.TraceIdContext;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 仅处理 V2 笔记/来源的请求冲突与前置条件；沿用既有 A0400 错误体。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {NoteController.class, SourceController.class})
public class NoteControllerAdvice {
    private final ApiErrorResponseFactory errors;

    public NoteControllerAdvice(ApiErrorResponseFactory errors) {
        this.errors = errors;
    }

    @ExceptionHandler(NoteFailure.class)
    public ResponseEntity<ApiErrorVO> handle(NoteFailure failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.failure()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PRECONDITION -> HttpStatus.PRECONDITION_FAILED;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(errors.create(TraceIdContext.resolve(request),
                        ErrorCode.INVALID_PARAM, failure.getMessage(), null));
    }
}
