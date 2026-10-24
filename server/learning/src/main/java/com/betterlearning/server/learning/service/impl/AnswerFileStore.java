package com.betterlearning.server.learning.service.impl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.config.AppFilesProperties;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;

import jakarta.servlet.http.Part;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** 仅管理本次候选与当前对象；不扫描历史目录，不启动清理任务。 */
@Component
public class AnswerFileStore {
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final Path staging;
    private final Path objects;
    private final JsonMapper json;

    public record Candidate(String fileRef, String requestHash) {
    }

    public AnswerFileStore(AppFilesProperties properties, JsonMapper json) {
        this.json = json;
        try {
            Path configured = Path.of(properties.getRoot());
            if (!configured.isAbsolute()) {
                throw new IllegalStateException("APP_FILES_ROOT 必须为绝对目录");
            }
            Files.createDirectories(configured);
            if (Files.isSymbolicLink(configured) || !Files.isWritable(configured)) {
                throw new IllegalStateException("APP_FILES_ROOT 必须为可写真实目录");
            }
            Path root = configured.toRealPath();
            staging = root.resolve("staging");
            objects = root.resolve("objects");
            Files.createDirectories(staging);
            Files.createDirectories(objects);
            if (Files.isSymbolicLink(staging) || Files.isSymbolicLink(objects)
                    || !Files.getFileStore(staging).equals(Files.getFileStore(objects))) {
                throw new IllegalStateException("文件临时区与对象区须为同一文件系统真实目录");
            }
            forceDirectory(root);
        } catch (IOException exception) {
            throw new IllegalStateException("APP_FILES_ROOT 不可用", exception);
        }
    }

    public Candidate save(AnswerModels.SaveManifest manifest, AnswerModels.QuestionSnapshot snapshot,
            Collection<Part> parts) {
        List<AnswerModels.Block> blocks = new ArrayList<>(manifest.blocks());
        blocks.addAll(manifest.document().questionLayout().rasterBlocks());
        Map<String, Part> received = new HashMap<>();
        for (Part part : parts) {
            if (received.putIfAbsent(part.getName(), part) != null) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
        }
        Set<String> expected = new HashSet<>();
        expected.add("manifest");
        blocks.forEach(block -> expected.add(block.partName()));
        if (!received.keySet().equals(expected)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        Part metadata = received.get("manifest");
        contentType(metadata, "application", "json");
        if (metadata.getSize() > AnswerManifestValidator.MAX_MANIFEST_BYTES) {
            throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
        }
        long total = 0;
        for (AnswerModels.Block block : blocks) {
            Part part = received.get(block.partName());
            contentType(part, "image", "png");
            total += part.getSize();
            if (part.getSize() > AnswerManifestValidator.MAX_PNG_BYTES
                    || total > AnswerManifestValidator.MAX_TOTAL_PNG_BYTES) {
                throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
            }
        }
        String fileRef = UUID.randomUUID().toString();
        Path temporary = staging.resolve(fileRef);
        boolean published = false;
        try {
            byte[] raw;
            try (InputStream input = metadata.getInputStream()) {
                raw = input.readNBytes((int) AnswerManifestValidator.MAX_MANIFEST_BYTES + 1);
            }
            if (raw.length > AnswerManifestValidator.MAX_MANIFEST_BYTES) {
                throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
            }
            Files.createDirectory(temporary);
            for (AnswerModels.Block block : blocks) {
                Path target = temporary.resolve(block.partName() + ".png");
                writePng(received.get(block.partName()), block, target);
            }
            byte[] stored = json.writeValueAsBytes(new AnswerModels.StoredManifest(manifest, snapshot));
            writeBytes(temporary.resolve("manifest.json"), stored);
            forceDirectory(temporary);
            Files.move(temporary, objects.resolve(fileRef), StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(staging);
            forceDirectory(objects);
            published = true;
            return new Candidate(fileRef, AnswerManifestValidator.sha256(raw));
        } catch (IOException exception) {
            throw unavailable(exception);
        } finally {
            if (!published) {
                deleteTree(temporary);
                deleteTree(objects.resolve(fileRef));
            }
        }
    }

    public AnswerModels.StoredManifest manifest(String fileRef) {
        try {
            Path path = object(fileRef).resolve("manifest.json");
            if (Files.isSymbolicLink(path) || Files.size(path) > 16L * 1024 * 1024) {
                throw new IOException("保存的文档不可用");
            }
            return json.readValue(Files.readAllBytes(path), AnswerModels.StoredManifest.class);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    public byte[] block(String fileRef, String partName) {
        Path path = object(fileRef).resolve(partName + ".png");
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > AnswerManifestValidator.MAX_PNG_BYTES) {
                throw unavailable(null);
            }
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    public void discard(Candidate candidate) {
        if (AnswerManifestValidator.uuid(candidate.fileRef())) {
            deleteTree(objects.resolve(candidate.fileRef()));
        }
    }

    private Path object(String fileRef) {
        if (!AnswerManifestValidator.uuid(fileRef)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        Path path = objects.resolve(fileRef);
        if (Files.isSymbolicLink(path)) {
            throw unavailable(null);
        }
        return path;
    }

    private static void writePng(Part part, AnswerModels.Block block, Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        long size = 0;
        try (InputStream input = part.getInputStream();
                FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] signature = input.readNBytes(PNG_SIGNATURE.length);
            if (!Arrays.equals(signature, PNG_SIGNATURE)) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
            digest.update(signature);
            write(channel, signature, signature.length);
            size += signature.length;
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > AnswerManifestValidator.MAX_PNG_BYTES) {
                    throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
                }
                digest.update(buffer, 0, read);
                write(channel, buffer, read);
            }
            channel.force(true);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(block.sha256())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        // 磁盘读取失败继续作为 IOException/503；内存中的格式解码失败才是 400。
        byte[] png = Files.readAllBytes(path);
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                if (!"png".equalsIgnoreCase(reader.getFormatName())
                        || reader.getWidth(0) != block.pixelWidth()
                        || reader.getHeight(0) != block.pixelHeight()) {
                    throw new BusinessException(ErrorCode.INVALID_PARAM);
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new BusinessException(ErrorCode.INVALID_PARAM);
                }
                image.flush();
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }

    private static void forceDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void writeBytes(Path path, byte[] value) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            write(channel, value, value.length);
            channel.force(true);
        }
    }

    private static void write(FileChannel channel, byte[] value, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(value, 0, length);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void contentType(Part part, String type, String subtype) {
        try {
            MediaType mediaType = MediaType.parseMediaType(part.getContentType());
            if (type.equals(mediaType.getType()) && subtype.equals(mediaType.getSubtype())) {
                return;
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    private static void deleteTree(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var files = Files.walk(path)) {
            for (Path entry : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException | UncheckedIOException exception) {
            // 仅清理本次明确归属候选；失败保留孤儿，不递归扫描其他对象。
        }
    }

    private static BusinessException unavailable(Throwable cause) {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "文件存储暂时不可用", null, cause);
    }
}
