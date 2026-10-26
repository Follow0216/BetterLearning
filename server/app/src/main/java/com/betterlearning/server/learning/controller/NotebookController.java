package com.betterlearning.server.learning.controller;

import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.learning.model.note.NoteModels;
import com.betterlearning.server.learning.service.NoteService;
import com.betterlearning.server.security.AuthenticatedAccount;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 单层笔记本；删除只清理归类，保留笔记来源与正文。 */
@RestController
@RequestMapping("/api/v2/learning/notebooks")
@Tag(name = "Notes")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
public class NotebookController {
    private final NoteService service;

    public NotebookController(NoteService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<NoteModels.Notebooks> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.notebooks(account.accountId()));
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "创建成功",
            content = @Content(schema = @Schema(implementation = NoteModels.Notebook.class)))
    public ResponseEntity<NoteModels.Notebook> create(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody NoteModels.NotebookWrite write) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.createNotebook(account.accountId(), key, write));
    }

    @PutMapping("/{notebookId}")
    public ResponseEntity<NoteModels.Notebook> rename(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String notebookId,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true)
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody NoteModels.NotebookWrite write) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.renameNotebook(account.accountId(), notebookId, ifMatch, write));
    }

    @DeleteMapping("/{notebookId}")
    @ApiResponse(responseCode = "204", description = "笔记本已删除，笔记保留为未归类", content = @Content)
    public ResponseEntity<Void> delete(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String notebookId,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true)
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestParam(defaultValue = "false") boolean confirm) {
        service.deleteNotebook(account.accountId(), notebookId, ifMatch, confirm);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
