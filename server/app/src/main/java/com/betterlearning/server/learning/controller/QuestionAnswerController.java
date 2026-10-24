package com.betterlearning.server.learning.controller;

import java.io.IOException;

import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.QuestionAnswerService;
import com.betterlearning.server.security.AuthenticatedAccount;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

/** 只适配认证账号与请求，文件不通过静态目录公开。 */
@RestController
@RequestMapping("/api/v1/learning/questions/{questionId}/answer")
@Tag(name = "当前作答")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
public class QuestionAnswerController {
    private final QuestionAnswerService service;

    public QuestionAnswerController(QuestionAnswerService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<AnswerModels.Detail> detail(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.detail(account.accountId(), questionId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AnswerModels.SaveResult save(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @RequestPart("manifest") AnswerModels.SaveManifest manifest,
            HttpServletRequest request) throws IOException, ServletException {
        return service.save(account.accountId(), questionId, manifest, request.getParts());
    }

    @GetMapping(value = "/files/{fileRef}/blocks/{index}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> block(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String fileRef, @PathVariable int index) {
        return image(service.block(account.accountId(), questionId, fileRef, index, false));
    }

    @GetMapping(value = "/files/{fileRef}/question-blocks/{index}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> questionBlock(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String fileRef, @PathVariable int index) {
        return image(service.block(account.accountId(), questionId, fileRef, index, true));
    }

    private static ResponseEntity<byte[]> image(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
