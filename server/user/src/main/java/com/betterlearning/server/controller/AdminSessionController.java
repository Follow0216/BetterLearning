package com.betterlearning.server.controller;

import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.model.vo.UserVO;
import com.betterlearning.server.security.AuthenticatedAccount;
import com.betterlearning.server.service.UserService;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 最小管理员会话探针；沿用既有 /api/v1/admin 路径的 ADMIN 安全规则。 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "管理员会话")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
public class AdminSessionController {

    private final UserService userService;

    public AdminSessionController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/session")
    public UserVO session(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account) {
        return userService.getProfile(account.accountId());
    }
}
