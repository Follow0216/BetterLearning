package com.betterlearning.server.agent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;

import com.betterlearning.server.agent.model.TutorModels;
import com.betterlearning.server.agent.service.OcrService;
import com.betterlearning.server.config.OpenApiConfiguration;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.security.AuthenticatedAccount;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单图短生命周期识别；不保存源图、不自动建立正式题目。 */
@RestController
@RequestMapping("/api/v1/learning/ocr")
@Tag(name = "题目识别")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
public class OcrController {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final OcrService ocr;

    public OcrController(OcrService ocr) {
        this.ocr = ocr;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TutorModels.OcrResult recognize(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedAccount account,
            HttpServletRequest request) throws ServletException, IOException {
        Collection<Part> parts = request.getParts();
        if (parts.size() != 1) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        Part image = parts.iterator().next();
        if (!"image".equals(image.getName())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        if (image.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
        }
        String contentType;
        try {
            MediaType type = MediaType.parseMediaType(image.getContentType());
            contentType = type.getType() + "/" + type.getSubtype();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        if (!"image/png".equals(contentType) && !"image/jpeg".equals(contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        byte[] bytes;
        try (InputStream input = image.getInputStream()) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
        }
        return ocr.recognize(contentType, bytes);
    }
}
