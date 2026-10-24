package com.betterlearning.server.agent.model;

import java.util.Objects;

/** 内部有界图像输入；不将图像字节写入默认toString或公开HTTP响应。 */
public final class ModelImage {
    private final String mediaType;
    private final byte[] bytes;
    private final String position;

    public ModelImage(String mediaType, byte[] bytes, String position) {
        this.mediaType = Objects.requireNonNull(mediaType);
        this.bytes = Objects.requireNonNull(bytes).clone();
        this.position = position == null ? "" : position;
    }

    public String mediaType() {
        return mediaType;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int byteCount() {
        return bytes.length;
    }

    public String position() {
        return position;
    }

    @Override
    public String toString() {
        return "ModelImage[mediaType=" + mediaType + ", byteCount=" + bytes.length + "]";
    }
}
