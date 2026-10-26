package com.betterlearning.server.agent.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.betterlearning.server.agent.model.BatchOcrModels.InputImage;
import com.betterlearning.server.agent.model.BatchOcrModels.Source;
import com.betterlearning.server.agent.model.BatchOcrModels.Tile;
import com.betterlearning.server.agent.model.ModelImage;

/** 压缩字节、实际格式和像素先验限额；只在单张图解码后裁剪整数矩形。 */
final class BatchOcrImageSupport {
    static final int MAX_SOURCES = 4;
    static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    static final long MAX_PIXELS = 16000000L;
    static final int MAX_EDGE = 16000;
    static final int MAX_TILES = 24;
    private static final int MAX_BASE_TILES = 16;
    private static final int OVERLAP = 256;

    private BatchOcrImageSupport() {
    }

    static List<Source> validate(List<InputImage> images) {
        if (images == null || images.isEmpty() || images.size() > MAX_SOURCES) {
            throw invalid("每批须包含1至4张图片");
        }
        long total = 0;
        Set<String> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        // 必须先检查完整批次的压缩字节限额，再解码任意图片。
        for (InputImage input : images) {
            if (input == null || input.sourceId() == null
                    || !input.sourceId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                    || !ids.add(input.sourceId()) || input.order() < 0 || input.order() >= images.size()
                    || !orders.add(input.order())) {
                throw invalid("图片来源或顺序无效");
            }
            if (!"image/png".equals(input.mediaType()) && !"image/jpeg".equals(input.mediaType())) {
                throw invalid("仅支持PNG或JPEG图片");
            }
            if (input.byteCount() < 1 || input.byteCount() > MAX_SOURCE_BYTES) {
                throw invalid("单张图片须小于等于16MiB");
            }
            total += input.byteCount();
        }
        if (total > MAX_TOTAL_BYTES) {
            throw invalid("整批图片须小于等于32MiB");
        }
        List<Source> sources = new ArrayList<>();
        for (InputImage input : images.stream().sorted(Comparator.comparingInt(InputImage::order)).toList()) {
            BufferedImage decoded = decode(input);
            try {
                sources.add(new Source(input.sourceId(), input.order(), decoded.getWidth(), decoded.getHeight()));
            } finally {
                decoded.flush();
            }
        }
        return List.copyOf(sources);
    }

    static BufferedImage decode(InputImage input) {
        try (MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(input.bytes()))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw invalid("图片实际内容不是可读取的PNG或JPEG");
            }
            ImageReader reader = readers.next();
            try {
                String actual = reader.getFormatName();
                boolean png = "PNG".equalsIgnoreCase(actual);
                boolean jpeg = "JPEG".equalsIgnoreCase(actual) || "JPG".equalsIgnoreCase(actual);
                if ((!png && !jpeg) || (png && !"image/png".equals(input.mediaType()))
                        || (jpeg && !"image/jpeg".equals(input.mediaType()))) {
                    throw invalid("图片实际格式与声明不一致");
                }
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_EDGE || height > MAX_EDGE
                        || (long) width * height > MAX_PIXELS) {
                    throw invalid("图片须不超过1600万像素，最长边不超过16000像素");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw invalid("图片内容不完整或尺寸无效");
                }
                return decoded;
            } finally {
                reader.dispose();
            }
        } catch (IOException | IndexOutOfBoundsException exception) {
            throw invalid("图片内容不完整或无法解码");
        }
    }

    static List<Tile> plan(List<Source> sources) {
        int extent = 2048;
        while (count(sources, extent) > MAX_BASE_TILES) {
            extent += 512;
        }
        List<Tile> tiles = new ArrayList<>();
        for (Source source : sources) {
            int index = 0;
            for (int y : starts(source.height(), extent)) {
                for (int x : starts(source.width(), extent)) {
                    tiles.add(new Tile(source.sourceId() + "-tile-" + index++, source.sourceId(), x, y,
                            Math.min(extent, source.width() - x), Math.min(extent, source.height() - y), false, ""));
                }
            }
        }
        return tiles;
    }

    static ModelImage crop(BufferedImage image, Tile tile) {
        BufferedImage crop = image.getSubimage(tile.x(), tile.y(), tile.width(), tile.height());
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(crop, "png", output)) {
                throw invalid("无法读取图片区域");
            }
            return new ModelImage("image/png", output.toByteArray(), "sourceId=" + tile.sourceId()
                    + "; tileId=" + tile.tileId() + "; x=" + tile.x() + "; y=" + tile.y()
                    + "; width=" + tile.width() + "; height=" + tile.height() + "; repair=" + tile.repair());
        } catch (IOException exception) {
            throw invalid("无法读取图片区域");
        } finally {
            crop.flush();
        }
    }

    static Tile repair(Source source, Tile tile, String edge, int index) {
        int width = Math.min(source.width(), Math.max(1024, tile.width()));
        int height = Math.min(source.height(), Math.max(1024, tile.height()));
        int x = tile.x() + (tile.width() - width) / 2;
        int y = tile.y() + (tile.height() - height) / 2;
        switch (edge) {
            case "TOP" -> y = tile.y() - height / 2;
            case "BOTTOM" -> y = tile.y() + tile.height() - height / 2;
            case "LEFT" -> x = tile.x() - width / 2;
            case "RIGHT" -> x = tile.x() + tile.width() - width / 2;
            default -> throw invalid("补识别边缘无效");
        }
        x = Math.max(0, Math.min(x, source.width() - width));
        y = Math.max(0, Math.min(y, source.height() - height));
        return new Tile(source.sourceId() + "-repair-" + index, source.sourceId(), x, y,
                width, height, true, "");
    }

    static boolean internalEdge(Source source, Tile tile, String edge) {
        return switch (edge) {
            case "TOP" -> tile.y() > 0;
            case "BOTTOM" -> tile.y() + tile.height() < source.height();
            case "LEFT" -> tile.x() > 0;
            case "RIGHT" -> tile.x() + tile.width() < source.width();
            default -> false;
        };
    }

    private static int count(List<Source> sources, int extent) {
        return sources.stream().mapToInt(source -> starts(source.width(), extent).size()
                * starts(source.height(), extent).size()).sum();
    }

    private static List<Integer> starts(int length, int extent) {
        List<Integer> starts = new ArrayList<>();
        int offset = 0;
        while (true) {
            starts.add(offset);
            if (offset + extent >= length) {
                return starts;
            }
            offset += extent - OVERLAP;
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
