package com.betterlearning.server.agent.batch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.betterlearning.server.agent.model.BatchOcrModels.InputImage;
import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.model.vo.ApiErrorVO;
import com.betterlearning.server.security.AuthenticatedAccount;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 批量 OCR 只生成可校对草稿，题目入库继续使用既有单题确认接口。 */
@RestController
@RequestMapping("/api/v1/learning/ocr/batches/{requestId}")
@Tag(name = "题目批量识别")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
@ApiResponse(responseCode = "401", description = "A0200 未认证或访问令牌已失效",
        content = @Content(schema = @Schema(implementation = ApiErrorVO.class)))
public class BatchOcrController {
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_BATCH_BYTES = 32 * 1024 * 1024;
    private final BatchOcrService service;

    public BatchOcrController(BatchOcrService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "提交批量题目识别",
            description = "同账号同请求 ID 与相同图片内容、顺序只返回原批次；不同内容返回 409 A0445。"
            + "返回草稿供用户校对，不自动确认入库。实际图像验证由有界 worker 执行，失败显示 FAILED。")
    @RequestBody(required = true,
            description = "上传 1 至 4 张 PNG/JPEG 图片，字段须从 image0 开始连续命名；"
            + "每张不超过 16 MiB，整批不超过 32 MiB，保留客户端原图用于校对。",
            content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            schema = @Schema(type = "object", requiredProperties = {"image0"},
            minProperties = 1, maxProperties = 4,
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE),
            schemaProperties = {
                    @SchemaProperty(name = "image0", schema = @Schema(type = "string", format = "binary")),
                    @SchemaProperty(name = "image1", schema = @Schema(type = "string", format = "binary")),
                    @SchemaProperty(name = "image2", schema = @Schema(type = "string", format = "binary")),
                    @SchemaProperty(name = "image3", schema = @Schema(type = "string", format = "binary"))},
            encoding = {
                    @Encoding(name = "image0", contentType = "image/png,image/jpeg"),
                    @Encoding(name = "image1", contentType = "image/png,image/jpeg"),
                    @Encoding(name = "image2", contentType = "image/png,image/jpeg"),
                    @Encoding(name = "image3", contentType = "image/png,image/jpeg")}))
    @ApiResponse(responseCode = "200", description = "返回已创建或已存在的批次与当前可校对内容",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = BatchOcrService.Response.class)))
    public ResponseEntity<BatchOcrService.Response> submit(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @Parameter(description = "客户端生成并在恢复时保持不变的标准 UUID",
            schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String requestId, HttpServletRequest request) throws IOException, ServletException {
        validateRequestId(requestId);
        List<Upload> uploads = uploads(request.getParts());
        String fingerprint = fingerprint(uploads);
        return result(service.submit(account.accountId(), requestId, fingerprint, () -> load(uploads)));
    }

    @GetMapping
    @Operation(summary = "查询批量题目识别状态", description = "只查询已持久化的状态与草稿，不发起或重跑模型调用。")
    @ApiResponse(responseCode = "200", description = "返回当前状态、进度与已识别内容",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = BatchOcrService.Response.class)))
    public ResponseEntity<BatchOcrService.Response> detail(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @Parameter(description = "客户端生成并在恢复时保持不变的标准 UUID",
            schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String requestId) {
        validateRequestId(requestId);
        return result(service.detail(account.accountId(), requestId));
    }

    @DeleteMapping
    @Operation(summary = "取消批量题目识别",
            description = "保留已识别的部分结果，阻止后续模型调用；正在执行的调用可能仍需等待结束。")
    @ApiResponse(responseCode = "200", description = "返回取消后的状态或已有终态，保留可校对内容",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = BatchOcrService.Response.class)))
    public ResponseEntity<BatchOcrService.Response> cancel(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            @Parameter(description = "客户端生成并在恢复时保持不变的标准 UUID",
            schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String requestId) {
        validateRequestId(requestId);
        return result(service.cancel(account.accountId(), requestId));
    }

    private static ResponseEntity<BatchOcrService.Response> result(BatchOcrService.Response response) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    private static void validateRequestId(String requestId) {
        try {
            if (!UUID.fromString(requestId).toString().equals(requestId)) {
                throw new IllegalArgumentException("Non-canonical request ID");
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "识别请求 ID 必须为标准 UUID");
        }
    }

    private record Upload(Part part, String mediaType) {
    }

    private static List<Upload> uploads(Collection<Part> parts) {
        if (parts.isEmpty() || parts.size() > 4) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "每批须上传 1 至 4 张图片");
        }
        Map<String, Part> byName = new HashMap<>();
        for (Part part : parts) {
            if (byName.put(part.getName(), part) != null) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
        }
        List<Upload> ordered = new ArrayList<>();
        long total = 0;
        for (int index = 0; index < parts.size(); index++) {
            Part part = byName.get("image" + index);
            if (part == null || part.getSize() <= 0) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
            total += part.getSize();
            if (part.getSize() > MAX_IMAGE_BYTES || total > MAX_BATCH_BYTES) {
                throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
            }
            String mediaType;
            try {
                MediaType parsed = MediaType.parseMediaType(part.getContentType());
                mediaType = (parsed.getType() + "/" + parsed.getSubtype()).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            }
            if (!"image/png".equals(mediaType) && !"image/jpeg".equals(mediaType)) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            }
            ordered.add(new Upload(part, mediaType));
        }
        return ordered;
    }

    private static String fingerprint(List<Upload> uploads) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
        digest.update("BATCH_OCR_V1".getBytes(StandardCharsets.UTF_8));
        digest.update(ByteBuffer.allocate(4).putInt(uploads.size()).array());
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int index = 0; index < uploads.size(); index++) {
            Upload upload = uploads.get(index);
            byte[] media = upload.mediaType().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(16).putInt(index).putInt(media.length)
                    .putLong(upload.part().getSize()).array());
            digest.update(media);
            int imageBytes = 0;
            try (InputStream input = upload.part().getInputStream()) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    imageBytes += read;
                    total += read;
                    if (imageBytes > MAX_IMAGE_BYTES || total > MAX_BATCH_BYTES) {
                        throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
                    }
                    digest.update(buffer, 0, read);
                }
            }
            if (imageBytes != upload.part().getSize()) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<InputImage> load(List<Upload> uploads) throws IOException {
        List<InputImage> images = new ArrayList<>();
        int total = 0;
        for (int index = 0; index < uploads.size(); index++) {
            Upload upload = uploads.get(index);
            byte[] bytes;
            try (InputStream input = upload.part().getInputStream()) {
                bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
            }
            total += bytes.length;
            if (bytes.length > MAX_IMAGE_BYTES || total > MAX_BATCH_BYTES) {
                throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
            }
            if (bytes.length != upload.part().getSize()) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
            images.add(new InputImage("image-" + index, index, upload.mediaType(), bytes));
        }
        return images;
    }
}
