package com.betterlearning.server.security;

import java.util.LinkedHashMap;
import java.util.Map;

import com.betterlearning.server.model.dto.UpdateProfileDTO;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("security-it")
@RequestMapping("/api/v1")
public class SecurityTestEndpoints {

    @GetMapping("/test/protected")
    public Map<String, Object> protectedEndpoint(
            @AuthenticationPrincipal AuthenticatedAccount account, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.accountId());
        body.put("username", account.username());
        body.put("role", account.role());
        body.put("tokenVersion", account.tokenVersion());
        body.put("jwtId", account.jwtId());
        body.put("hasSession", request.getSession(false) != null);
        return body;
    }

    @PostMapping("/test/profile-probe")
    public Map<String, Object> profileProbe(@RequestBody UpdateProfileDTO request) {
        return Map.of("accepted", true);
    }

    @GetMapping("/admin/test/ping")
    public Map<String, Object> adminEndpoint(
            @AuthenticationPrincipal AuthenticatedAccount account) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.accountId());
        body.put("role", account.role());
        return body;
    }
}
