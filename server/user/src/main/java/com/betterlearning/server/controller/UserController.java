package com.betterlearning.server.controller;

import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.model.dto.ChangePasswordDTO;
import com.betterlearning.server.model.dto.RegisterUserDTO;
import com.betterlearning.server.model.dto.UpdateProfileDTO;
import com.betterlearning.server.model.vo.ApiErrorVO;
import com.betterlearning.server.model.vo.UserVO;
import com.betterlearning.server.security.AuthenticatedAccount;
import com.betterlearning.server.service.UserService;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户相关 HTTP 入口。
 *
 * <p>只负责请求绑定、{@code @Valid} 触发的字段校验、调用业务服务并返回 VO；不在本层做输入值
 * 规范化或业务规则判断，账号 ID 一律取自认证 principal。业务异常由全局异常处理器转换。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Tag(name = "用户", description = "注册、个人资料与密码管理")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    /**
     * 创建用户控制器。
     *
     * @param userService 用户业务服务
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "注册用户",
            description = "创建角色固定为 USER 的账号。用户名去除首尾空白后英文字母转小写；"
            + "密码不做 trim。请求体只接受 username、password、confirmPassword。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "注册成功，返回用户信息",
                    content = @Content(schema = @Schema(implementation = UserVO.class))),
            @ApiResponse(responseCode = "400",
                    description = "A0400 参数不合法；A0427 请求体格式错误或含未知字段",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "409", description = "A0111 用户名已存在",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "429", description = "A0501 该 IP 注册过于频繁",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "503",
                    description = "C0001 数据库或 Redis 不可用",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class)))})
    /**
     * 注册用户。
     *
     * @param request 注册请求体，由 {@code @Valid} 触发字段校验
     * @param httpRequest 当前请求，用于读取客户端 IP 供限流
     * @return 新用户资料
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserVO register(@Valid @RequestBody RegisterUserDTO request,
            HttpServletRequest httpRequest) {
        return userService.register(request, httpRequest.getRemoteAddr());
    }

    @Operation(summary = "获取当前用户资料",
            description = "账号取自认证上下文，不接受客户端传入账号 ID。",
            security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "当前用户资料",
                    content = @Content(schema = @Schema(implementation = UserVO.class))),
            @ApiResponse(responseCode = "401", description = "A0200 未认证或令牌已失效",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "503",
                    description = "C0001 数据库或 Redis 不可用",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class)))})
    /**
     * 查询当前登录用户资料。
     *
     * @param account 认证上下文中的账号快照
     * @return 当前用户资料
     */
    @GetMapping("/me")
    public UserVO getProfile(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account) {
        return userService.getProfile(account.accountId());
    }

    @Operation(summary = "更新当前用户联系方式",
            description = "只处理请求体中出现的字段：缺失表示不修改；显式 null 表示解绑；"
            + "空串或非法格式返回 400 A0400。非空 phone/email 全局唯一，"
            + "冲突返回 409 且保留原值。请求体不接受账号、用户名或角色。",
            security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新后的用户资料",
                    content = @Content(schema = @Schema(implementation = UserVO.class))),
            @ApiResponse(responseCode = "400",
                    description = "A0400 联系方式为空串或格式错误；A0427 请求体格式错误或含未知字段",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "401", description = "A0200 未认证或令牌已失效",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "409",
                    description = "A0154 该手机号已绑定；A0155 该邮箱已绑定",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "503",
                    description = "C0001 数据库或 Redis 不可用",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class)))})
    /**
     * 按 PATCH 语义更新当前用户联系方式。
     *
     * @param account 认证上下文中的账号快照
     * @param request 联系方式更新请求体，由 {@code @Valid} 触发格式校验
     * @return 更新后的用户资料
     */
    @PatchMapping("/me")
    public UserVO updateProfile(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody UpdateProfileDTO request) {
        return userService.updateProfile(account.accountId(), request);
    }

    @Operation(summary = "修改当前用户密码",
            description = "校验旧密码后在同一个事务内更新密码哈希并递增 token_version，"
            + "所有旧令牌立即失效；成功返回 204 且无响应体，不签发新令牌。",
            security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "修改成功，无响应体"),
            @ApiResponse(responseCode = "400",
                    description = "A0400 参数不合法或旧密码错误（fieldErrors.oldPassword）；"
                            + "A0427 请求体格式错误或含未知字段",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "401", description = "A0200 未认证或令牌已失效",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "409",
                    description = "A0444 密码已变更，请重新登录",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class))),
            @ApiResponse(responseCode = "503",
                    description = "C0001 数据库或 Redis 不可用",
                    content = @Content(schema = @Schema(implementation = ApiErrorVO.class)))})
    /**
     * 修改当前用户密码，成功返回 204。
     *
     * @param account 认证上下文中的账号快照，提供账户 ID 与令牌版本
     * @param request 改密请求体，由 {@code @Valid} 触发字段校验
     */
    @PatchMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody ChangePasswordDTO request) {
        userService.changePassword(account.accountId(), account.tokenVersion(), request);
    }
}
