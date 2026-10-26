package com.betterlearning.server.agent.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import com.betterlearning.server.agent.config.AgentProperties;
import com.betterlearning.server.agent.model.AgentModelException;
import com.betterlearning.server.agent.model.AgentModelException.Failure;
import com.betterlearning.server.agent.model.BatchOcrCancelledException;
import com.betterlearning.server.agent.model.BatchOcrModels.InputImage;
import com.betterlearning.server.agent.model.BatchOcrModels.Result;
import com.betterlearning.server.agent.model.BatchOcrModels.Tile;
import com.betterlearning.server.agent.model.BatchOcrProcessingException;
import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.service.BatchOcrEngine.Observer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 只用本地图像、可控latch和伪模型，验证真实在途收束及owner线程边界。 */
class BatchOcrConcurrencyTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void rollsWindowAcrossSourcesAndKeepsCoordinatesTextOrderAndOwnerProgress() throws Exception {
        ControlledOcr model = new ControlledOcr();
        AtomicInteger assembled = new AtomicInteger();
        List<Integer> progress = new ArrayList<>();
        Set<Thread> callbacks = ConcurrentHashMap.newKeySet();
        BatchOcrEngine engine = new BatchOcrEngine(model, (sources, tiles, last) -> {
            assertThat(model.active.get()).isZero();
            assembled.incrementAndGet();
            return assembled(tiles, List.of());
        });
        Observer observer = new Observer() {
            @Override
            public boolean shouldCancel() {
                callbacks.add(Thread.currentThread());
                return false;
            }

            @Override
            public void partial(Result result) {
                callbacks.add(Thread.currentThread());
            }

            @Override
            public void progress(int completed, int total, String stage) {
                callbacks.add(Thread.currentThread());
                progress.add(completed);
            }
        };
        Run run = run(engine, List.of(image("image-1", 1, 100), image("image-0", 0, 7000)), observer);
        try {
            model.awaitStarted("image-0-tile-0", "image-0-tile-1", "image-0-tile-2");
            assertThat(model.active.get()).isEqualTo(3);
            model.release("image-0-tile-2");
            model.awaitStarted("image-0-tile-3");
            model.release("image-0-tile-1");
            model.awaitStarted("image-1-tile-0");
            assertThat(assembled.get()).isZero();
            model.release("image-1-tile-0", "image-0-tile-3", "image-0-tile-0");
            Result result = run.result.get(5, TimeUnit.SECONDS);
            assertThat(result.incomplete()).isFalse();
            assertThat(result.sources()).extracting(source -> source.sourceId())
                    .containsExactly("image-0", "image-1");
            assertThat(result.tiles()).extracting(Tile::tileId).containsExactly("image-0-tile-0",
                    "image-0-tile-1", "image-0-tile-2", "image-0-tile-3", "image-1-tile-0");
            assertThat(result.tiles()).allSatisfy(tile -> assertThat(tile.markdown()).isEqualTo(tile.tileId()));
            assertThat(result.tiles().subList(0, 4)).extracting(Tile::y).containsExactly(0, 1792, 3584, 5376);
            assertThat(progress).containsExactly(0, 1, 2, 3, 4, 5, 5);
            assertThat(callbacks).containsExactly(run.owner);
            assertThat(model.maximum.get()).isEqualTo(3);
            assertThat(assembled.get()).isEqualTo(1);
        } finally {
            model.releaseAll();
            run.join();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 8})
    void honorsConfiguredConcurrencyWithoutSubmittingTheWholeBatch(int concurrency) throws Exception {
        ControlledOcr model = new ControlledOcr();
        BatchOcrEngine engine = new BatchOcrEngine(model,
                (sources, tiles, last) -> assembled(tiles, List.of()), properties(concurrency));
        Run run = run(engine, List.of(image("image-0", 0, 16000)), null);
        try {
            for (int index = 0; index < concurrency; index++) {
                model.awaitStarted("image-0-tile-" + index);
            }
            assertThat(model.active.get()).isEqualTo(concurrency);
            assertThat(model.started.poll(150, TimeUnit.MILLISECONDS)).isNull();
            model.releaseAll();
            Result result = run.result.get(5, TimeUnit.SECONDS);
            assertThat(result.tiles()).hasSize(9);
            assertThat(model.calls.get()).isEqualTo(9);
            assertThat(model.maximum.get()).isEqualTo(concurrency);
        } finally {
            model.releaseAll();
            run.join();
        }
    }

    @Test
    void cancellationStopsDispatchAndWaitsForEveryIssuedCallKeepingLateSuccesses() throws Exception {
        ControlledOcr model = new ControlledOcr();
        AtomicBoolean cancel = new AtomicBoolean();
        CountDownLatch cancellationSeen = new CountDownLatch(1);
        Observer observer = new Observer() {
            @Override
            public boolean shouldCancel() {
                if (cancel.get()) {
                    cancellationSeen.countDown();
                    return true;
                }
                return false;
            }
        };
        Run run = run(noAssembly(model), List.of(image("image-0", 0, 9000)), observer);
        try {
            model.awaitStarted("image-0-tile-0", "image-0-tile-1", "image-0-tile-2");
            cancel.set(true);
            await(cancellationSeen);
            model.release("image-0-tile-2");
            await(model.gate("image-0-tile-2").exited);
            assertThat(run.result).isNotDone();
            assertThat(model.started.poll(150, TimeUnit.MILLISECONDS)).isNull();
            model.release("image-0-tile-0", "image-0-tile-1");
            BatchOcrCancelledException failure = failure(run, BatchOcrCancelledException.class);
            assertThat(failure.partialResult().tiles().subList(0, 3))
                    .allSatisfy(tile -> assertThat(tile.markdown()).isEqualTo(tile.tileId()));
            assertThat(failure.partialResult().tiles().subList(3, 5))
                    .allSatisfy(tile -> assertThat(tile.markdown()).isEmpty());
            assertThat(model.calls.get()).isEqualTo(3);
            assertThat(model.active.get()).isZero();
        } finally {
            model.releaseAll();
            run.join();
        }
    }

    @Test
    void outOfOrderFailureStopsNewCallsBeforeBlockedEarlierTileAndPreservesOtherSuccesses() throws Exception {
        ControlledOcr model = new ControlledOcr();
        model.gate("image-0-tile-1").fail = true;
        Run run = run(noAssembly(model), List.of(image("image-0", 0, 9000)), null);
        try {
            model.awaitStarted("image-0-tile-0", "image-0-tile-1", "image-0-tile-2");
            model.release("image-0-tile-1");
            await(model.gate("image-0-tile-1").exited);
            assertThat(model.started.poll(200, TimeUnit.MILLISECONDS)).isNull();
            model.release("image-0-tile-0");
            await(model.gate("image-0-tile-0").exited);
            assertThat(run.result).isNotDone();
            model.release("image-0-tile-2");
            BatchOcrProcessingException failure = failure(run, BatchOcrProcessingException.class);
            assertThat(failure.failure()).isEqualTo(Failure.MODEL_UNAVAILABLE);
            assertThat(failure.partialResult().tiles().get(0).markdown()).isEqualTo("image-0-tile-0");
            assertThat(failure.partialResult().tiles().get(1).markdown()).isEmpty();
            assertThat(failure.partialResult().tiles().get(2).markdown()).isEqualTo("image-0-tile-2");
            assertThat(model.calls.get()).isEqualTo(3);
            assertThat(model.active.get()).isZero();
        } finally {
            model.releaseAll();
            run.join();
        }
    }

    @Test
    void repairRoundUsesTheSameParallelWindowAndAssemblesOnlyAfterAllRepairs() throws Exception {
        ControlledOcr model = new ControlledOcr();
        for (int index = 0; index < 4; index++) {
            model.release("image-0-tile-" + index);
        }
        AtomicInteger assemblies = new AtomicInteger();
        BatchOcrEngine engine = new BatchOcrEngine(model, (sources, tiles, last) -> {
            assertThat(model.active.get()).isZero();
            assemblies.incrementAndGet();
            List<Map<String, String>> repairs = last ? List.of() : List.of(
                    repair("image-0-tile-0"), repair("image-0-tile-1"));
            return assembled(tiles, repairs);
        });
        Run run = run(engine, List.of(image("image-0", 0, 7000)), null);
        try {
            model.awaitStarted("image-0-tile-0", "image-0-tile-1", "image-0-tile-2", "image-0-tile-3",
                    "image-0-repair-0", "image-0-repair-1");
            assertThat(model.active.get()).isEqualTo(2);
            assertThat(assemblies.get()).isEqualTo(1);
            model.release("image-0-repair-0");
            await(model.gate("image-0-repair-0").exited);
            assertThat(assemblies.get()).isEqualTo(1);
            model.release("image-0-repair-1");
            Result result = run.result.get(5, TimeUnit.SECONDS);
            assertThat(assemblies.get()).isEqualTo(2);
            assertThat(result.tiles().subList(4, 6)).extracting(Tile::tileId)
                    .containsExactly("image-0-repair-0", "image-0-repair-1");
            assertThat(result.tiles()).hasSize(6);
        } finally {
            model.releaseAll();
            run.join();
        }
    }

    @Test
    void interruptedOwnerDrainsCallsAndRestoresInterruptWithoutInterruptingModels() throws Exception {
        ControlledOcr model = new ControlledOcr();
        Run run = run(noAssembly(model), List.of(image("image-0", 0, 9000)), null);
        try {
            model.awaitStarted("image-0-tile-0", "image-0-tile-1", "image-0-tile-2");
            run.owner.interrupt();
            assertThat(model.started.poll(200, TimeUnit.MILLISECONDS)).isNull();
            assertThat(run.result).isNotDone();
            model.releaseAll();
            BatchOcrCancelledException failure = failure(run, BatchOcrCancelledException.class);
            assertThat(failure.partialResult().tiles().subList(0, 3))
                    .allSatisfy(tile -> assertThat(tile.markdown()).isEqualTo(tile.tileId()));
            run.join();
            assertThat(run.interrupted.get()).isTrue();
            assertThat(model.interrupted.get()).isFalse();
            assertThat(model.calls.get()).isEqualTo(3);
        } finally {
            model.releaseAll();
            run.join();
        }
    }

    @Test
    void persistentObserverFailureStillDrainsAndKeepsEverySuccessfulTile() throws Exception {
        ControlledOcr model = new ControlledOcr();
        AtomicBoolean fail = new AtomicBoolean();
        CountDownLatch failedQuery = new CountDownLatch(1);
        AtomicInteger failingQueries = new AtomicInteger();
        Observer observer = new Observer() {
            @Override
            public boolean shouldCancel() {
                if (fail.get()) {
                    failingQueries.incrementAndGet();
                    failedQuery.countDown();
                    throw new IllegalStateException("离线模拟数据库中断");
                }
                return false;
            }

            @Override
            public void partial(Result result) {
                if (fail.get()) {
                    throw new IllegalStateException("离线模拟数据库中断");
                }
            }
        };
        Run run = run(noAssembly(model), List.of(image("image-0", 0, 9000)), observer);
        try {
            model.awaitStarted("image-0-tile-0", "image-0-tile-1", "image-0-tile-2");
            fail.set(true);
            await(failedQuery);
            model.release("image-0-tile-0");
            await(model.gate("image-0-tile-0").exited);
            assertThat(run.result).isNotDone();
            model.releaseAll();
            BatchOcrProcessingException failure = failure(run, BatchOcrProcessingException.class);
            assertThat(failure.partialResult().tiles().subList(0, 3))
                    .allSatisfy(tile -> assertThat(tile.markdown()).isEqualTo(tile.tileId()));
            assertThat(failingQueries.get()).isEqualTo(1);
            assertThat(model.calls.get()).isEqualTo(3);
            assertThat(model.active.get()).isZero();
        } finally {
            model.releaseAll();
            run.join();
        }
    }

    @Test
    void totalTextBudgetIsAllocatedInTileOrderDespiteReverseCompletion() throws Exception {
        ControlledOcr model = new ControlledOcr();
        // 四张长图各四片，共16片；12片*20000恰好用完整批字数预算。
        List<InputImage> images = new ArrayList<>();
        for (int source = 0; source < 4; source++) {
            images.add(image("image-" + source, source, 7000));
            for (int tile = 0; tile < 4; tile++) {
                model.gate("image-" + source + "-tile-" + tile).text = String.valueOf(source).repeat(20000);
            }
        }
        Run run = run(noAssembly(model, 8), images, null);
        try {
            for (int index = 0; index < 8; index++) {
                model.awaitStarted(id(index));
            }
            // 第一片保持阻塞；后续结果乱序返回并滚动启动剩余所有片。
            for (int index = 7; index >= 1; index--) {
                model.release(id(index));
            }
            for (int index = 8; index < 16; index++) {
                model.awaitStarted(id(index));
                model.release(id(index));
            }
            model.release(id(0));
            BatchOcrProcessingException failure = failure(run, BatchOcrProcessingException.class);
            assertThat(failure.failure()).isEqualTo(Failure.MODEL_RESPONSE_TOO_LARGE);
            assertThat(failure.partialResult().tiles().subList(0, 12))
                    .allSatisfy(tile -> assertThat(tile.markdown()).hasSize(20000));
            assertThat(failure.partialResult().tiles().subList(12, 16))
                    .allSatisfy(tile -> assertThat(tile.markdown()).isEmpty());
            assertThat(failure.partialResult().tiles().stream().mapToInt(tile -> tile.markdown().length()).sum())
                    .isEqualTo(240000);
        } finally {
            model.releaseAll();
            run.join();
        }
    }

    @Test
    void submittedButNotStartedTileSkipsModelAfterCancellation() throws Exception {
        ControlledOcr model = new ControlledOcr();
        CountDownLatch delayedWorker = new CountDownLatch(1);
        CountDownLatch submittedWorker = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicInteger threads = new AtomicInteger();
        BatchOcrEngine engine = new BatchOcrEngine(model, (sources, tiles, last) -> {
            throw new AssertionError("取消后不能重组");
        }, properties(2), task -> {
            int index = threads.getAndIncrement();
            return new Thread(() -> {
                if (index == 1) {
                    submittedWorker.countDown();
                    uninterruptible(delayedWorker, new AtomicBoolean());
                }
                task.run();
            }, "controlled-ocr-tile-" + index);
        });
        Run run = run(engine, List.of(image("image-0", 0, 7000)), new Observer() {
            @Override
            public boolean shouldCancel() {
                if (cancel.get()) {
                    cancelled.countDown();
                    return true;
                }
                return false;
            }
        });
        try {
            model.awaitStarted("image-0-tile-0");
            await(submittedWorker);
            cancel.set(true);
            await(cancelled);
            // owner 已从取消回调返回，进入completion轮询，停止标记已经可见。
            org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
                    run.owner.getState() == Thread.State.TIMED_WAITING);
            delayedWorker.countDown();
            assertThat(model.started.poll(150, TimeUnit.MILLISECONDS)).isNull();
            assertThat(run.result).isNotDone();
            model.releaseAll();
            failure(run, BatchOcrCancelledException.class);
            assertThat(model.calls.get()).isEqualTo(1);
        } finally {
            delayedWorker.countDown();
            model.releaseAll();
            run.join();
        }
    }

    private static String id(int index) {
        return "image-" + index / 4 + "-tile-" + index % 4;
    }

    private static BatchOcrEngine noAssembly(ControlledOcr model) {
        return noAssembly(model, 3);
    }

    private static BatchOcrEngine noAssembly(ControlledOcr model, int concurrency) {
        return new BatchOcrEngine(model, (sources, tiles, last) -> {
            throw new AssertionError("失败或取消后不能重组");
        }, properties(concurrency));
    }

    private static AgentProperties properties(int concurrency) {
        AgentProperties properties = new AgentProperties();
        properties.getOcr().setBatchConcurrency(concurrency);
        return properties;
    }

    private static Map<String, String> repair(String tileId) {
        return Map.of("sourceId", "image-0", "tileId", tileId, "edge", "BOTTOM", "reason", "跨缝");
    }

    private static String assembled(List<Tile> tiles, List<Map<String, String>> repairs) {
        List<Map<String, Object>> questions = tiles.stream().map(Tile::sourceId).distinct().map(source ->
                Map.<String, Object>of("bodyMarkdown", "题目", "sourceIds", List.of(source),
                "tileIds", tiles.stream().filter(tile -> tile.sourceId().equals(source)).map(Tile::tileId).toList(),
                "needsReview", false, "warnings", List.of())).toList();
        return JSON.writeValueAsString(Map.of("questions", questions,
                "coverage", tiles.stream().map(tile -> Map.of("tileId", tile.tileId(), "status", "USED")).toList(),
                "repairs", repairs, "warnings", List.of()));
    }

    private static InputImage image(String id, int order, int height) throws IOException {
        BufferedImage image = new BufferedImage(60, height, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return new InputImage(id, order, "image/png", output.toByteArray());
        } finally {
            image.flush();
        }
    }

    private static Run run(BatchOcrEngine engine, List<InputImage> images, Observer observer) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread owner = new Thread(() -> {
            try {
                result.complete(engine.process(images, observer));
            } catch (Throwable exception) {
                result.completeExceptionally(exception);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "controlled-ocr-owner");
        owner.start();
        return new Run(owner, result, interrupted);
    }

    private static <T extends Throwable> T failure(Run run, Class<T> type) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> run.result.get(5, TimeUnit.SECONDS));
        assertThat(failure.getCause()).isInstanceOf(type);
        return type.cast(failure.getCause());
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void uninterruptible(CountDownLatch latch, AtomicBoolean interrupted) {
        boolean retry = true;
        while (retry) {
            try {
                latch.await();
                retry = false;
            } catch (InterruptedException exception) {
                interrupted.set(true);
            }
        }
    }

    private record Run(Thread owner, CompletableFuture<Result> result, AtomicBoolean interrupted) {
        private void join() throws InterruptedException {
            owner.join(5000);
            assertThat(owner.isAlive()).isFalse();
        }
    }

    private static final class Gate {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch exited = new CountDownLatch(1);
        private boolean fail;
        private String text;
    }

    private static final class ControlledOcr implements OcrModel {
        private final Map<String, Gate> gates = new ConcurrentHashMap<>();
        private final LinkedBlockingQueue<String> started = new LinkedBlockingQueue<>();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximum = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean releaseEverything = new AtomicBoolean();
        private final AtomicBoolean interrupted = new AtomicBoolean();
        private final Set<String> seen = ConcurrentHashMap.newKeySet();

        private Gate gate(String id) {
            return gates.computeIfAbsent(id, ignored -> new Gate());
        }

        @Override
        public String recognize(ModelImage image) {
            String id = image.position().split("tileId=", 2)[1].split(";", 2)[0];
            Gate gate = gate(id);
            calls.incrementAndGet();
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.add(id);
            try {
                if (!releaseEverything.get()) {
                    uninterruptible(gate.release, interrupted);
                }
                if (gate.fail) {
                    throw new AgentModelException(Failure.MODEL_UNAVAILABLE);
                }
                return gate.text == null ? id : gate.text;
            } finally {
                active.decrementAndGet();
                gate.exited.countDown();
            }
        }

        private void awaitStarted(String... ids) throws InterruptedException {
            for (String id : ids) {
                while (!seen.contains(id)) {
                    String next = started.poll(5, TimeUnit.SECONDS);
                    assertThat(next).as("等待分片开始: %s", id).isNotNull();
                    seen.add(next);
                }
            }
        }

        private void release(String... ids) {
            for (String id : ids) {
                gate(id).release.countDown();
            }
        }

        private void releaseAll() {
            releaseEverything.set(true);
            gates.values().forEach(gate -> gate.release.countDown());
        }
    }
}
