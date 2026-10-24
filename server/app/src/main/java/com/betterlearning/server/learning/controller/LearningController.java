package com.betterlearning.server.learning.controller;

import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.QuestionAnswerService;
import com.betterlearning.server.learning.model.dto.ConfirmQuestionDTO;
import com.betterlearning.server.learning.model.dto.CreateQuestionCollectionDTO;
import com.betterlearning.server.learning.model.dto.RenameQuestionCollectionDTO;
import com.betterlearning.server.learning.model.dto.MoveQuestionDTO;
import com.betterlearning.server.learning.model.vo.QuestionCollectionVO;
import com.betterlearning.server.learning.model.vo.QuestionCollectionsVO;
import com.betterlearning.server.learning.model.vo.QuestionPageVO;
import com.betterlearning.server.learning.model.vo.QuestionVO;
import com.betterlearning.server.learning.service.LearningService;
import com.betterlearning.server.security.AuthenticatedAccount;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 只绑定请求并把已认证账号 ID 交给学习服务；无客户端 owner 参数。 */
@RestController
@RequestMapping("/api/v1/learning")
@Tag(name = "题库")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
public class LearningController {

    private final LearningService learningService;
    private final QuestionAnswerService answerService;

    public LearningController(LearningService learningService, QuestionAnswerService answerService) {
        this.learningService = learningService;
        this.answerService = answerService;
    }

    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionVO confirm(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody ConfirmQuestionDTO request) {
        return learningService.confirm(account.accountId(), request);
    }

    @GetMapping("/questions/{questionId}")
    public QuestionVO detail(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId) {
        return learningService.detail(account.accountId(), questionId);
    }

    @GetMapping("/questions")
    public QuestionPageVO page(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) String collectionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return learningService.page(account.accountId(), collectionId, page, pageSize);
    }

    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionCollectionVO createCollection(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody CreateQuestionCollectionDTO request) {
        return learningService.createCollection(account.accountId(), request);
    }

    @PatchMapping("/collections/{collectionId}")
    public QuestionCollectionVO renameCollection(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String collectionId, @Valid @RequestBody RenameQuestionCollectionDTO request) {
        return learningService.renameCollection(account.accountId(), collectionId, request);
    }

    @PatchMapping("/questions/{questionId}/collection")
    public QuestionVO moveQuestion(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @Valid @RequestBody MoveQuestionDTO request) {
        return learningService.moveQuestion(account.accountId(), questionId, request);
    }

    @PatchMapping("/questions/{questionId}/answer-state")
    public AnswerModels.SaveResult answerState(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @RequestBody AnswerModels.StateRequest request) {
        return answerService.changeState(account.accountId(), questionId, request);
    }

    @GetMapping("/collections")
    public QuestionCollectionsVO collections(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account) {
        return learningService.collections(account.accountId());
    }
}
