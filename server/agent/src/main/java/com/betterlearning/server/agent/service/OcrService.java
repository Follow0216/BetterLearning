package com.betterlearning.server.agent.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.betterlearning.server.agent.model.AgentModelException;
import com.betterlearning.server.agent.model.AgentModelException.Failure;
import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.model.TutorModels;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;

import org.springframework.beans.factory.ObjectProvider;

/** 短生命周期OCR；不写文件、不入库，输出由Phone校对后走M1确认。 */
public class OcrService {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final long MAX_PIXELS = 16L * 1024 * 1024;
    private final ObjectProvider<OcrModel> models;

    public OcrService(ObjectProvider<OcrModel> models) {
        this.models = models;
    }

    public TutorModels.OcrResult recognize(String mediaType, byte[] bytes) {
        validate(mediaType, bytes);
        OcrModel model = models.getIfAvailable();
        if (model == null) {
            throw new AgentModelException(Failure.MODEL_NOT_CONFIGURED);
        }
        String markdown = model.recognize(new ModelImage(mediaType, bytes, "单题裁剪图"));
        if (markdown == null || markdown.isBlank()) {
            throw new AgentModelException(Failure.MODEL_EMPTY_RESPONSE);
        }
        if (markdown.length() > 20000) {
            throw new AgentModelException(Failure.MODEL_RESPONSE_TOO_LARGE);
        }
        return new TutorModels.OcrResult(markdown);
    }

    private static void validate(String mediaType, byte[] bytes) {
        if (!"image/png".equals(mediaType) && !"image/jpeg".equals(mediaType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        if (bytes.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
        }
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                boolean matching = "image/png".equals(mediaType) ? "png".equalsIgnoreCase(format)
                        : "jpeg".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!matching || width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new BusinessException(ErrorCode.INVALID_PARAM);
                }
                // 完整解码确认图片实际可读；不把只含伪造尺寸头的字节发给供应商。
                if (reader.read(0) == null) {
                    throw new BusinessException(ErrorCode.INVALID_PARAM);
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }
}
