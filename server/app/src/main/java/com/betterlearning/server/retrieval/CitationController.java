package com.betterlearning.server.retrieval;

import com.betterlearning.server.security.AuthenticatedAccount;
import com.betterlearning.server.config.OpenApiConfiguration;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 引用内容与原图只经实时账号授权读取，不开放静态文件路径。 */
@RestController
@RequestMapping("/api/v2/learning/citations")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
public class CitationController {
    private final CitationService service;

    public CitationController(CitationService service) {
        this.service = service;
    }

    @GetMapping("/{citationId}/resolve")
    public ResponseEntity<CitationService.Resolution> resolve(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account, @PathVariable String citationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(service.resolve(account.accountId(), citationId));
    }

    @GetMapping(value = "/{citationId}/assets/{blockIndex}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> asset(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String citationId, @PathVariable int blockIndex) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(service.asset(account.accountId(), citationId, blockIndex));
    }
}
