package com.betterlearning.server.agent;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import com.betterlearning.server.agent.config.AgentProperties;
import com.betterlearning.server.agent.model.AgentModelException;
import com.betterlearning.server.agent.model.AgentModelException.Failure;
import com.betterlearning.server.agent.model.BatchOcrCancelledException;
import com.betterlearning.server.agent.model.BatchOcrModels.InputImage;
import com.betterlearning.server.agent.model.BatchOcrModels.Result;
import com.betterlearning.server.agent.model.BatchOcrModels.Source;
import com.betterlearning.server.agent.model.BatchOcrModels.Tile;
import com.betterlearning.server.agent.model.BatchOcrProcessingException;
import com.betterlearning.server.agent.service.BatchOcrEngine;
import com.betterlearning.server.agent.service.BatchOcrEngine.Observer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 小量本地图像+伪模型边界样本，不调用供应商或设备。 */
class BatchOcrEngineTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void longImageRetainsOverlappingCoordinatesAndMultipleQuestions() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        BatchOcrEngine engine = new BatchOcrEngine(image -> {
            calls.incrementAndGet();
            assertTrue(image.position().contains("sourceId=image-0"));
            assertTrue(image.position().contains("x=0"));
            return "1. 计算 $1+2$。\n2. 比较 $3$ 与 $4$。";
        }, (sources, tiles, finalRound) -> response(List.of(
                question("1. 计算 $1+2$。", "image-0", tiles, false),
                question("2. 比较 $3$ 与 $4$。", "image-0", tiles, false)), tiles, List.of(), false));

        Result result = engine.process(List.of(image("image-0", 0, 240, 4300)), null);

        assertFalse(result.incomplete());
        assertEquals(2, result.questions().size());
        assertEquals(3, calls.get());
        assertEquals(4300, result.sources().get(0).height());
        assertEquals("item-0", result.questions().get(0).itemId());
        Tile first = result.tiles().get(0);
        Tile second = result.tiles().get(1);
        assertTrue(first.y() + first.height() > second.y());
        assertEquals(4300, result.tiles().get(2).y() + result.tiles().get(2).height());
        assertTrue(result.tiles().stream().allMatch(tile -> !tile.markdown().isBlank()));
    }

    @Test
    void preservesSeparateSourceOrderEvenWithIdenticalQuestionNumbers() throws IOException {
        BatchOcrEngine engine = new BatchOcrEngine(image -> "1. 原图独立题目。", (sources, tiles, finalRound) -> {
            List<Map<String, Object>> reversed = new ArrayList<>();
            reversed.add(question("1. 第二张图的题目。", "image-1", tiles, false));
            reversed.add(question("1. 第一张图的题目。", "image-0", tiles, false));
            return response(reversed, tiles, List.of(), false);
        });

        Result result = engine.process(List.of(image("image-1", 1, 80, 80), image("image-0", 0, 80, 80)), null);

        assertFalse(result.incomplete());
        assertEquals(List.of("image-0", "image-1"), result.sources().stream().map(Source::sourceId).toList());
        assertEquals(List.of("image-0"), result.questions().get(0).sourceIds());
        assertEquals(List.of("image-1"), result.questions().get(1).sourceIds());
    }

    @Test
    void repairsInternalEdgeOnlyOnceAndKeepsUncertainFormulaIncomplete() throws IOException {
        AtomicInteger assemblyCalls = new AtomicInteger();
        AtomicInteger ocrCalls = new AtomicInteger();
        BatchOcrEngine engine = new BatchOcrEngine(image -> {
            ocrCalls.incrementAndGet();
            return "题干 $x+ [待核对：公式在边缘截断]$";
        }, (sources, tiles, finalRound) -> {
            assemblyCalls.incrementAndGet();
            List<Map<String, String>> repair = finalRound ? List.of() : List.of(Map.of("sourceId", "image-0",
                    "tileId", tiles.get(0).tileId(), "edge", "BOTTOM", "reason", "公式跨缝"));
            return response(List.of(question("题干 $x+[待核对：缺失公式]$", "image-0", tiles, true)),
                    tiles, repair, false);
        });

        Result result = engine.process(List.of(image("image-0", 0, 180, 3300)), null);

        assertTrue(result.incomplete());
        assertEquals(2, assemblyCalls.get());
        assertEquals(3, ocrCalls.get());
        Tile repair = result.tiles().stream().filter(Tile::repair).findFirst().orElseThrow();
        assertTrue(repair.y() < 2048);
        assertTrue(repair.y() + repair.height() > 2048);
        assertEquals("image-0", repair.sourceId());
        assertTrue(result.questions().get(0).needsReview());
    }

    @Test
    void successfulRepairCanResolveTheOriginalEdgeMarker() throws IOException {
        BatchOcrEngine engine = new BatchOcrEngine(image -> image.position().contains("repair=true")
                ? "题干 $x+1=3$。" : "题干 $x+[待核对：边缘截断]$", (sources, tiles, finalRound) -> {
                    List<Map<String, String>> repair = finalRound ? List.of() : List.of(Map.of("sourceId", "image-0",
                            "tileId", tiles.get(0).tileId(), "edge", "BOTTOM", "reason", "公式跨缝"));
                    return response(List.of(question(finalRound ? "题干 $x+1=3$。" : "题干 $x+[待核对]$",
                            "image-0", tiles, !finalRound)), tiles, repair, false);
                });

        Result result = engine.process(List.of(image("image-0", 0, 180, 3300)), null);

        assertFalse(result.incomplete());
        assertFalse(result.questions().get(0).needsReview());
        assertTrue(result.tiles().get(0).markdown().contains("待核对"));
        assertTrue(result.tiles().stream().anyMatch(Tile::repair));
    }

    @Test
    void missingCoverageAndForgedRegionNeverBecomeACompleteEmptyResult() throws IOException {
        BatchOcrEngine missing = new BatchOcrEngine(image -> "可见题目", (sources, tiles, finalRound) ->
                JSON.writeValueAsString(Map.of("questions", List.of(), "coverage", List.of(),
                "repairs", List.of(), "warnings", List.of())));
        Result missingResult = missing.process(List.of(image("image-0", 0, 80, 80)), null);
        assertTrue(missingResult.incomplete());
        assertEquals("可见题目", missingResult.tiles().get(0).markdown());

        BatchOcrEngine forged = new BatchOcrEngine(image -> "可见题目", (sources, tiles, finalRound) ->
                response(List.of(question("可见题目", "image-0", tiles, false)), tiles,
                List.of(Map.of("sourceId", "image-0", "tileId", tiles.get(0).tileId(),
                "edge", "BOTTOM", "reason", "伪造外边缘")), false));
        Result forgedResult = forged.process(List.of(image("image-0", 0, 80, 80)), null);
        assertTrue(forgedResult.incomplete());
        assertEquals(1, forgedResult.tiles().size());
        assertTrue(forgedResult.warnings().stream().anyMatch(value -> value.contains("来源引用无效")));
    }

    @Test
    void cancellationAndProviderFailurePreserveCompletedTileText() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        BatchOcrEngine engine = new BatchOcrEngine(image -> {
            calls.incrementAndGet();
            return "已转写的第一片";
        }, (sources, tiles, finalRound) -> {
            throw new AssertionError("取消后不能重组");
        }, serialProperties());
        Observer cancel = new Observer() {
            @Override
            public boolean shouldCancel() {
                return calls.get() > 0;
            }
        };
        BatchOcrCancelledException cancelled = assertThrows(BatchOcrCancelledException.class,
                () -> engine.process(List.of(image("image-0", 0, 120, 3300)), cancel));
        assertTrue(cancelled.partialResult().incomplete());
        assertEquals("已转写的第一片", cancelled.partialResult().tiles().get(0).markdown());
        assertEquals(1, calls.get());

        AtomicInteger failures = new AtomicInteger();
        BatchOcrEngine unavailable = new BatchOcrEngine(image -> {
            if (failures.incrementAndGet() == 2) {
                throw new AgentModelException(Failure.MODEL_UNAVAILABLE);
            }
            return "故障前已转写";
        }, (sources, tiles, finalRound) -> "{}", serialProperties());
        BatchOcrProcessingException failed = assertThrows(BatchOcrProcessingException.class,
                () -> unavailable.process(List.of(image("image-0", 0, 120, 3300)), null));
        assertEquals(Failure.MODEL_UNAVAILABLE, failed.failure());
        assertEquals("故障前已转写", failed.partialResult().tiles().get(0).markdown());
        assertEquals(2, failures.get());
    }

    @Test
    void validatesActualFormatDimensionsAndWholeBatchLimitsBeforeCallingModels() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        BatchOcrEngine engine = new BatchOcrEngine(image -> {
            calls.incrementAndGet();
            return "";
        }, (sources, tiles, finalRound) -> "{}");
        byte[] png = image("image-0", 0, 60, 60).bytes();
        assertThrows(IllegalArgumentException.class, () -> engine.process(List.of(
                new InputImage("image-0", 0, "image/jpeg", png)), null));
        assertThrows(IllegalArgumentException.class, () -> engine.process(List.of(
                new InputImage("image-0", 0, "image/png", new byte[]{1, 2, 3})), null));
        assertThrows(IllegalArgumentException.class,
                () -> engine.process(List.of(image("image-0", 0, 1, 16001)), null));
        byte[] overPixels = png.clone();
        ByteBuffer.wrap(overPixels).putInt(16, 4001).putInt(20, 4000);
        CRC32 crc = new CRC32();
        crc.update(overPixels, 12, 17);
        ByteBuffer.wrap(overPixels).putInt(29, (int) crc.getValue());
        IllegalArgumentException pixels = assertThrows(IllegalArgumentException.class,
                () -> engine.validateInputs(List.of(new InputImage("image-0", 0, "image/png", overPixels))));
        assertTrue(pixels.getMessage().contains("1600万像素"));
        byte[] oversized = new byte[16 * 1024 * 1024 + 1];
        assertThrows(IllegalArgumentException.class, () -> engine.process(List.of(
                new InputImage("image-0", 0, "image/png", oversized)), null));
        assertThrows(IllegalArgumentException.class, () -> engine.process(List.of(
                new InputImage("image-0", 0, "image/png", png),
                new InputImage("image-0", 1, "image/png", png)), null));
        assertEquals(0, calls.get());
        BufferedImage jpegImage = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(jpegImage, "jpeg", output);
            List<Source> jpeg = engine.validateInputs(List.of(new InputImage("image-0", 0, "image/jpeg",
                    output.toByteArray())));
            assertEquals(40, jpeg.get(0).width());
            assertEquals(30, jpeg.get(0).height());
        } finally {
            jpegImage.flush();
        }
    }

    @Test
    void explicitEmptyTilesRemainDistinctFromIncompleteAndFailedRuns() throws IOException {
        BatchOcrEngine engine = new BatchOcrEngine(image -> "[无文字]", (sources, tiles, finalRound) ->
                response(List.of(), tiles, List.of(), true));
        Result result = engine.process(List.of(image("image-0", 0, 80, 80)), null);
        assertFalse(result.incomplete());
        assertTrue(result.questions().isEmpty());
        assertEquals("[无文字]", result.tiles().get(0).markdown());
    }

    private static AgentProperties serialProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getOcr().setBatchConcurrency(1);
        return properties;
    }

    private static Map<String, Object> question(String body, String sourceId, List<Tile> tiles, boolean review) {
        return Map.of("bodyMarkdown", body, "sourceIds", List.of(sourceId), "tileIds", tiles.stream()
                .filter(tile -> tile.sourceId().equals(sourceId)).map(Tile::tileId).toList(),
                "needsReview", review, "warnings", review ? List.of("公式需要校对") : List.of());
    }

    private static String response(List<Map<String, Object>> questions, List<Tile> tiles,
            List<Map<String, String>> repairs, boolean empty) {
        return JSON.writeValueAsString(Map.of("questions", questions, "coverage", tiles.stream()
                .map(tile -> Map.of("tileId", tile.tileId(), "status", empty ? "EMPTY" : "USED")).toList(),
                "repairs", repairs, "warnings", List.of()));
    }

    private static InputImage image(String sourceId, int order, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return new InputImage(sourceId, order, "image/png", output.toByteArray());
        } finally {
            image.flush();
        }
    }
}
