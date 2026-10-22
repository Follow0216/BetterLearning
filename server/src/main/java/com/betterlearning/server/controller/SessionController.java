package com.betterlearning.server.controller;

import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.model.dto.CreateSessionDTO;
import com.betterlearning.server.model.vo.ApiErrorVO;
import com.betterlearning.server.model.vo.SessionVO;
import com.betterlearning.server.security.AuthenticatedAccount;
import com.betterlearning.server.service.SessionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话相关 HTTP 入口。
 *
 * <p>只负责请求绑定、{@code @Valid} 触发的字段校验与调用业务服务；登录限流、凭据校验、
 * 令牌签发与会话写入均在业务层完成。退出使用的 jti 取自认证 principal。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Tag(name = "会话", description = "登录与退出")
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;

    /**
     * 创建会话控制器。
     *
     * @param sessionService 会话业务服务
     */
    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Operation(summary = "登录",
            description = "校验用户名和密码后签发 HS256 JWT，并在 Redis 写入与令牌同期的会话白名单。"
            + "用户名去除首尾空白后英文字母转小写；密码不做 trim。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "登录成功，返回令牌与用户信息",
                    content = @Content(schema = @Schema(implementation = SessionVO.class))),
            @ApiResponse(responseCode = "400",
                    description = "A0400 参数不合法；A0427 请求体格式错误或含未知字段",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "401",
                    description = "A0200 用户名或密码错误",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "429", description = "A0501 登录尝试过于频繁",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "503",
                    description = "C0001 Redis 或数据库不可用",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class)))})
    /**
     * 登录并签发会话令牌。
     *
     * @param request 登录请求体，由 {@code @Valid} 触发字段校验
     * @param httpRequest 当前请求，用于读取客户端 IP 供限流
     * @return 令牌、剩余有效秒数与用户资料
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionVO login(@Valid @RequestBody CreateSessionDTO request,
            HttpServletRequest httpRequest) {
        return sessionService.login(request, httpRequest.getRemoteAddr());
    }

    @Operation(summary = "退出当前会话",
            description = "仅删除认证上下文中的当前 jti 对应会话，不影响同账号其他端会话；"
            + "不修改账号 token_version，不签发或返回令牌。成功返回 204 且无响应体。",
            security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "退出成功，无响应体"),
            @ApiResponse(responseCode = "401", description = "A0200 未认证或令牌已失效",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "503",
                    description = "C0001 会话存储不可用，无法确认退出结果",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class)))})
    /**
     * 退出当前会话，成功返回 204。
     *
     * @param account 认证上下文中的账号快照，提供当前 jti
     */
    @DeleteMapping("/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account) {
        sessionService.logout(account.jwtId());
    }
}
