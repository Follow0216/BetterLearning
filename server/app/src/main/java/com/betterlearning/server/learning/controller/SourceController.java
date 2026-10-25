package com.betterlearning.server.learning.controller;

import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.learning.service.NoteService;
import com.betterlearning.server.security.AuthenticatedAccount;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** V2 来源只读列表，仅返回 OpenAPI 公共字段。 */
@RestController
@RequestMapping("/api/v2/learning/sources")
@Tag(name = "Sources")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
public class SourceController {
    private final NoteService service;

    public SourceController(NoteService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<NoteService.SourcePage> list(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) String type, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.sources(account.accountId(), type, cursor, limit));
    }

    @GetMapping("/{sourceId}")
    public ResponseEntity<NoteService.SourceView> get(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.source(account.accountId(), sourceId));
    }
}
