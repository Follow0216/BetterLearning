package com.betterlearning.server.learning.validation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;

/** 笔记字节边界与幂等指纹；正文仅统一 CRLF，不改写公式或空格。 */
public record NoteContent(String title, String bodyMarkdown, String contentSha256, String requestSha256) {
    public static final int MAX_BODY_BYTES = 64 * 1024;

    public static NoteContent validate(String title, String body) {
        if (title == null || title.isBlank() || body == null || body.isBlank()) {
            throw invalid();
        }
        validateUnicode(title);
        validateUnicode(body);
        String normalized = body.replace("\r\n", "\n");
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "笔记正文不能超过 64 KiB UTF-8 字节");
        }
        String contentHash = sha256(bytes);
        // 长度前缀消除标题/正文分隔符歧义，指纹包含标题。
        byte[] request = (title.length() + ":" + title + normalized).getBytes(StandardCharsets.UTF_8);
        return new NoteContent(title, normalized, contentHash, sha256(request));
    }

    public static String id(String value) {
        if (value == null) {
            throw invalid();
        }
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) {
                throw invalid();
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    public static String etag(String noteId, long version) {
        return "\"" + noteId + ":" + version + "\"";
    }

    public static boolean matches(String ifMatch, String noteId, long version) {
        return etag(noteId, version).equals(ifMatch);
    }

    private static void validateUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw invalid();
                }
            } else if (Character.isLowSurrogate(character) || character == 0) {
                throw invalid();
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_PARAM, "笔记参数不合法");
    }
}
