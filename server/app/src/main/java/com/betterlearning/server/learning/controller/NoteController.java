package com.betterlearning.server.learning.controller;

import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.learning.model.note.NoteModels;
import com.betterlearning.server.learning.service.NoteService;
import com.betterlearning.server.learning.validation.NoteContent;
import com.betterlearning.server.security.AuthenticatedAccount;

import io.swagger.v3.oas.annotations.Operation;
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

/** V2 笔记 HTTP 边界，账号只取认证 principal。 */
@RestController
@RequestMapping("/api/v2/learning/notes")
@Tag(name = "Notes")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
public class NoteController {
    private final NoteService service;

    public NoteController(NoteService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<NoteModels.Page> list(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String q, @RequestParam(required = false) String notebookId,
            @RequestParam(required = false) String questionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(q == null && notebookId == null && questionId == null
                        ? service.list(account.accountId(), cursor, limit)
                        : service.search(account.accountId(), q, notebookId, questionId, cursor, limit));
    }

    @PostMapping
    @Operation(summary = "创建笔记")
    @ApiResponse(responseCode = "201", description = "创建成功",
            content = @Content(schema = @Schema(implementation = NoteModels.Note.class)))
    public ResponseEntity<NoteModels.Note> create(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
            description = "创建笔记的幂等键；缺失返回 400")
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody NoteModels.Write write) {
        NoteModels.Note note = service.create(account.accountId(), key, write);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .eTag(NoteContent.etag(note.noteId(), note.version())).body(note);
    }

    @GetMapping("/{noteId}")
    public ResponseEntity<NoteModels.Note> get(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String noteId) {
        NoteModels.Note note = service.get(account.accountId(), noteId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(NoteContent.etag(note.noteId(), note.version())).body(note);
    }

    @PutMapping("/{noteId}")
    public ResponseEntity<NoteModels.Note> update(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String noteId,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true,
            description = "当前笔记的强 ETag；缺失返回 400")
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody NoteModels.Write write) {
        NoteModels.Note note = service.update(account.accountId(), noteId, ifMatch, write);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(NoteContent.etag(note.noteId(), note.version())).body(note);
    }

    @PutMapping("/{noteId}/context")
    public ResponseEntity<NoteModels.Note> file(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String noteId,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true,
            description = "独立归类版本标签，正文版本不随归类变化")
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody NoteModels.Filing filing) {
        NoteModels.Note note = service.file(account.accountId(), noteId, ifMatch, filing);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(note);
    }

    @DeleteMapping("/{noteId}")
    @Operation(summary = "删除笔记")
    @ApiResponse(responseCode = "204", description = "删除成功", content = @Content)
    public ResponseEntity<Void> delete(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String noteId,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true,
            description = "当前笔记的强 ETag；缺失返回 400")
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        service.delete(account.accountId(), noteId, ifMatch);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
