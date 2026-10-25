package com.betterlearning.server.agent;

import java.io.IOException;

import com.betterlearning.server.agent.model.TutorModels;
import com.betterlearning.server.agent.service.TutorService;
import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.model.tutor.TutorStorageModels;
import com.betterlearning.server.learning.service.FrozenAnswerService;
import com.betterlearning.server.learning.service.RolloutGate;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

/** 仅适配已认证账号、固定HTTP契约及完整接受后触发模型。 */
@RestController
@RequestMapping("/api/v1/learning/questions/{questionId}/tutor-turns")
@Tag(name = "跨端辅导")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
public class TutorController {
    private final TutorService tutor;
    private final FrozenAnswerService frozen;
    private final RolloutGate rollout;

    public TutorController(TutorService tutor, FrozenAnswerService frozen, RolloutGate rollout) {
        this.tutor = tutor;
        this.frozen = frozen;
        this.rollout = rollout;
    }

    public record EmptyRequest() {
    }

    @PostMapping
    public TutorModels.Turn create(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @RequestBody TutorModels.CreateRequest request) {
        return tutor.create(account.accountId(), questionId, request);
    }

    @GetMapping
    public TutorModels.TurnList list(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId) {
        return tutor.list(account.accountId(), questionId);
    }

    @GetMapping("/pending")
    public TutorModels.Pending pending(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId) {
        return tutor.pending(account.accountId(), questionId);
    }

    @GetMapping("/{turnId}")
    public TutorModels.Turn get(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String turnId) {
        return tutor.get(account.accountId(), questionId, turnId);
    }

    @PostMapping("/{turnId}/retry")
    public TutorModels.Turn retry(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String turnId, @RequestBody EmptyRequest request) {
        rollout.require("LEGACY");
        return tutor.generate(account.accountId(), questionId, turnId, true);
    }

    @PostMapping("/{turnId}/cancel")
    public TutorModels.Turn cancel(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String turnId, @RequestBody EmptyRequest request) {
        return tutor.cancel(account.accountId(), questionId, turnId);
    }

    @PostMapping(value = "/{turnId}/answer", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TutorModels.Turn accept(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String turnId,
            @RequestPart("manifest") AnswerModels.SaveManifest manifest, HttpServletRequest request)
            throws ServletException, IOException {
        rollout.require("LEGACY");
        TutorStorageModels.Acceptance accepted = frozen.accept(account.accountId(), questionId, turnId,
                manifest, request.getParts());
        // 已绑定但尚未领取时，原包重复上传也可领取；失败轮只允许显式retry。
        if ("WAITING_ANSWER".equals(accepted.turn().getStatus())) {
            return tutor.generate(account.accountId(), questionId, turnId, false);
        }
        return TutorTurnStoreAdapter.turn(accepted.turn());
    }

    @GetMapping("/{turnId}/answer")
    public ResponseEntity<AnswerModels.ReadManifest> answer(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String turnId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(frozen.manifest(account.accountId(), questionId, turnId));
    }

    @GetMapping(value = "/{turnId}/answer/files/{fileRef}/blocks/{index}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> block(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String turnId,
            @PathVariable String fileRef, @PathVariable int index) {
        return image(frozen.block(account.accountId(), questionId, turnId, fileRef, index, false));
    }

    @GetMapping(value = "/{turnId}/answer/files/{fileRef}/question-blocks/{index}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> raster(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId, @PathVariable String turnId,
            @PathVariable String fileRef, @PathVariable int index) {
        return image(frozen.block(account.accountId(), questionId, turnId, fileRef, index, true));
    }

    private static ResponseEntity<byte[]> image(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
