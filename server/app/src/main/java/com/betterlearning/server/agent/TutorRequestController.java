package com.betterlearning.server.agent;

import java.io.IOException;
import java.util.Map;

import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.security.AuthenticatedAccount;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

/** 已认证 V2 辅导请求 HTTP 适配器。 */
@RestController
@RequestMapping("/api/v2/learning/questions/{questionId}/tutor-requests")
@Tag(name = "新版辅导请求")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
public class TutorRequestController {
    private final TutorRequestService service;

    public TutorRequestController(TutorRequestService service) {
        this.service = service;
    }

    @PostMapping
    public TutorRequestModels.Request create(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TutorRequestModels.CreateRequest input) {
        return service.create(account.accountId(), questionId, idempotencyKey, input);
    }

    @GetMapping
    public TutorRequestModels.Page list(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return service.list(account.accountId(), questionId, cursor, limit);
    }

    @GetMapping("/pending")
    public TutorRequestModels.Pending pending(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId) {
        return service.pending(account.accountId(), questionId);
    }

    @GetMapping("/{requestId}")
    public TutorRequestModels.Detail detail(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String requestId) {
        return service.detail(account.accountId(), questionId, requestId);
    }

    @PostMapping(value = "/{requestId}/snapshot", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TutorRequestModels.Detail upload(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String requestId,
            @RequestPart("manifest") AnswerModels.SaveManifest manifest, HttpServletRequest request)
            throws IOException, ServletException {
        return service.upload(account.accountId(), questionId, requestId, manifest, request.getParts());
    }

    @PostMapping("/{requestId}/cancel")
    public TutorRequestModels.Detail cancel(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String requestId,
            @RequestBody Map<String, Object> input) {
        if (input == null || input.size() != 1 || !input.containsKey("expectedRunId")
                || (input.get("expectedRunId") != null && !(input.get("expectedRunId") instanceof String))) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        return service.cancel(account.accountId(), questionId, requestId,
                new TutorRequestModels.CancelRequest((String) input.get("expectedRunId")));
    }

    @PostMapping("/{requestId}/retry")
    public TutorRequestModels.Detail retry(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String requestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TutorRequestModels.RetryRequest input) {
        return service.retry(account.accountId(), questionId, requestId, idempotencyKey, input);
    }
}
