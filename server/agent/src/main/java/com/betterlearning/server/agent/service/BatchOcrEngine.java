package com.betterlearning.server.agent.service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.betterlearning.server.agent.config.AgentProperties;

import com.betterlearning.server.agent.model.AgentModelException;
import com.betterlearning.server.agent.model.AgentModelException.Failure;
import com.betterlearning.server.agent.model.BatchOcrCancelledException;
import com.betterlearning.server.agent.model.BatchOcrModels.InputImage;
import com.betterlearning.server.agent.model.BatchOcrModels.Question;
import com.betterlearning.server.agent.model.BatchOcrModels.Result;
import com.betterlearning.server.agent.model.BatchOcrModels.Source;
import com.betterlearning.server.agent.model.BatchOcrModels.Tile;
import com.betterlearning.server.agent.model.BatchOcrProcessingException;
import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.service.BatchOcrAssemblyValidator.Assembly;
import com.betterlearning.server.agent.service.BatchOcrAssemblyValidator.Repair;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 有界的图片切片、忠实转写和独立模型重组；不写正式题库，不自动重试模型。 */
@Component
public final class BatchOcrEngine {
    private static final int MAX_TILE_TEXT = 20000;
    private static final int MAX_TOTAL_TEXT = 240000;
    private final OcrModel ocr;
    private final BatchOcrAssembler assembler;
    private final int concurrency;
    private final ThreadFactory tileThreads;

    public BatchOcrEngine(OcrModel ocr, BatchOcrAssembler assembler) {
        this(ocr, assembler, new AgentProperties());
    }

    @Autowired
    public BatchOcrEngine(OcrModel ocr, BatchOcrAssembler assembler, AgentProperties properties) {
        this(ocr, assembler, properties, task -> {
            Thread thread = new Thread(task, "batch-ocr-tile");
            thread.setDaemon(true);
            return thread;
        });
    }

    BatchOcrEngine(OcrModel ocr, BatchOcrAssembler assembler, AgentProperties properties,
            ThreadFactory tileThreads) {
        this.ocr = ocr;
        this.assembler = assembler;
        this.concurrency = properties.getOcr().getBatchConcurrency();
        this.tileThreads = tileThreads;
    }

    public interface Observer {
        default boolean shouldCancel() {
            return false;
        }

        default void progress(int completed, int total, String stage) {
        }

        default void partial(Result result) {
        }
    }

    public List<Source> validateInputs(List<InputImage> images) {
        return BatchOcrImageSupport.validate(images);
    }

    public Result process(List<InputImage> images, Observer observer) {
        Observer events = observer == null ? new Observer() { } : observer;
        if (events.shouldCancel() || Thread.currentThread().isInterrupted()) {
            throw new BatchOcrCancelledException(new Result(List.of(), List.of(), List.of(),
                    List.of("批量识别已取消，原图尚未处理"), true));
        }
        State state = new State(validateInputs(images), events);
        List<Tile> planned = BatchOcrImageSupport.plan(state.sources);
        state.tiles.addAll(planned);
        state.total = planned.size();
        state.publish("OCR");
        state.checkCancel();
        try {
            recognize(images, planned, state, "OCR");
            Assembly first = assemble(state, false);
            if (first == null) {
                return state.result(true);
            }
            state.questions = first.questions();
            if (first.repairs().isEmpty()) {
                state.warnings.addAll(first.warnings());
                return state.finish(first.incomplete());
            }
            state.warnings.addAll(first.warnings());
            state.publish("REPAIR");
            List<Tile> repairs = repairs(first.repairs(), state);
            if (repairs.isEmpty()) {
                state.warnings.add("无法在补识别限额内修复跨缝内容，请按原片校对");
                return state.finish(true);
            }
            state.tiles.addAll(repairs);
            state.total += repairs.size();
            recognize(images, repairs, state, "REPAIR");
            Assembly last = assemble(state, true);
            if (last == null) {
                return state.result(true);
            }
            state.questions = last.questions();
            state.warnings.removeAll(first.warnings());
            state.warnings.addAll(last.warnings());
            if (!last.repairs().isEmpty()) {
                state.warnings.add("已完成唯一补识别轮次，仍有跨缝内容需要人工校对");
            }
            return state.finish(last.incomplete() || !last.repairs().isEmpty());
        } catch (BatchOcrCancelledException exception) {
            throw exception;
        } catch (AgentModelException exception) {
            state.warnings.add("模型调用未完成，已识别的原片文字仍可校对");
            Result partial = state.finishAfterFailure();
            throw new BatchOcrProcessingException(exception.failure(), partial);
        } catch (RuntimeException exception) {
            state.warnings.add("处理未完成，已识别的原片文字仍可校对");
            throw new BatchOcrProcessingException(Failure.MODEL_UNAVAILABLE, state.finishAfterFailure());
        }
    }

    private void recognize(List<InputImage> images, List<Tile> requests, State state, String stage) {
        // 仅协调线程裁图、更新 State 和调用 Observer。窗口内至多 concurrency 张编码图，
        // 不预生成整批图片；完成队列和执行队列也显式有界。
        ThreadPoolExecutor pool = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(concurrency), tileThreads, new ThreadPoolExecutor.AbortPolicy());
        ExecutorCompletionService<Recognition> completions = new ExecutorCompletionService<>(pool,
                new ArrayBlockingQueue<>(concurrency));
        AtomicBoolean stopped = new AtomicBoolean();
        Map<Integer, Recognition> pending = new HashMap<>();
        int submitted = 0;
        int received = 0;
        int next = 0;
        boolean cancelled = false;
        boolean interrupted = false;
        RuntimeException failure = null;
        try (TileImages crops = new TileImages(images)) {
            while (true) {
                try {
                    if (!cancelled && failure == null && state.cancelRequested()) {
                        cancelled = true;
                        stopped.set(true);
                    }
                    // 优先收走所有已结束任务，再补窗口；worker 发现异常会立即阻止派新片，
                    // 即使较早的片仍未结束，也不会因等待稳定顺序而漏掉失败信号。
                    Future<Recognition> ready;
                    while ((ready = completions.poll()) != null) {
                        Recognition result = ready.get();
                        received++;
                        pending.put(result.index(), result);
                    }
                    while (pending.containsKey(next)) {
                        Recognition result = pending.remove(next);
                        RuntimeException rejected = accept(requests.get(next++), result, state, stage);
                        if (failure == null && rejected != null) {
                            failure = rejected;
                            stopped.set(true);
                        }
                    }
                    while (!stopped.get() && submitted < requests.size()
                            && submitted - received < concurrency) {
                        if (state.cancelRequested()) {
                            cancelled = true;
                            stopped.set(true);
                            break;
                        }
                        ModelImage image = crops.crop(requests.get(submitted));
                        if (stopped.get()) {
                            break;
                        }
                        if (state.cancelRequested()) {
                            cancelled = true;
                            stopped.set(true);
                            break;
                        }
                        int index = submitted;
                        completions.submit(() -> recognizeTile(index, image, stopped));
                        submitted++;
                    }
                    if (received == submitted && (stopped.get() || submitted == requests.size())) {
                        break;
                    }
                    // 定时回到 owner 查询取消；绝不取消 Future 来假装底层 HTTP 已经退出。
                    ready = completions.poll(100, TimeUnit.MILLISECONDS);
                    if (ready != null) {
                        Recognition result = ready.get();
                        received++;
                        pending.put(result.index(), result);
                    }
                } catch (InterruptedException exception) {
                    interrupted = true;
                    cancelled = true;
                    stopped.set(true);
                } catch (ExecutionException exception) {
                    // recognizeTile 将模型失败变成值；这里只处理不可预期的执行器故障。
                    received++;
                    stopped.set(true);
                    if (failure == null) {
                        failure = new IllegalStateException("OCR执行未完成", exception.getCause());
                    }
                } catch (RuntimeException exception) {
                    stopped.set(true);
                    if (failure == null) {
                        failure = exception;
                    }
                }
            }
            // 若某个执行器任务未返回 Recognition，跳过其空洞，仍按片序保留其余成功结果。
            for (int index = next; index < submitted; index++) {
                Recognition result = pending.get(index);
                if (result != null) {
                    RuntimeException rejected = accept(requests.get(index), result, state, stage);
                    if (failure == null && rejected != null) {
                        failure = rejected;
                    }
                }
            }
        } finally {
            pool.shutdown();
            boolean terminated = false;
            while (!terminated) {
                try {
                    terminated = pool.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                    cancelled = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (cancelled) {
            state.cancel();
        }
        if (failure != null) {
            throw failure;
        }
        state.checkCancel();
    }

    private Recognition recognizeTile(int index, ModelImage image, AtomicBoolean stopped) {
        try {
            if (stopped.get()) {
                return new Recognition(index, null, null);
            }
            String markdown = ocr.recognizeTile(image);
            if (markdown == null) {
                throw new AgentModelException(Failure.MODEL_EMPTY_RESPONSE);
            }
            if (markdown.length() > MAX_TILE_TEXT) {
                stopped.set(true);
                return new Recognition(index, markdown.substring(0, MAX_TILE_TEXT),
                        new AgentModelException(Failure.MODEL_RESPONSE_TOO_LARGE));
            }
            return new Recognition(index, markdown, null);
        } catch (RuntimeException exception) {
            stopped.set(true);
            return new Recognition(index, null, exception);
        } catch (Error error) {
            stopped.set(true);
            return new Recognition(index, null, new IllegalStateException("OCR执行未完成", error));
        }
    }

    private RuntimeException accept(Tile tile, Recognition result, State state, String stage) {
        if (result.markdown() == null) {
            return result.failure();
        }
        int remaining = MAX_TOTAL_TEXT - state.tiles.stream().mapToInt(item -> item.markdown().length()).sum();
        int allowed = Math.min(MAX_TILE_TEXT, Math.max(0, remaining));
        boolean oversized = result.markdown().length() > allowed || result.failure() != null;
        state.replace(tile, result.markdown().substring(0, Math.min(allowed, result.markdown().length())));
        if (oversized) {
            state.warnings.add("原片文字超过长度上限，仅保留可校对前缀：" + tile.tileId());
        } else {
            state.completed++;
        }
        state.publish(stage);
        return oversized ? new AgentModelException(Failure.MODEL_RESPONSE_TOO_LARGE) : null;
    }

    private record Recognition(int index, String markdown, RuntimeException failure) {
    }

    /** 仅在 owner 线程访问；切换来源立即释放上一张解码图。 */
    private static final class TileImages implements AutoCloseable {
        private final List<InputImage> inputs;
        private BufferedImage decoded;
        private String sourceId;

        private TileImages(List<InputImage> inputs) {
            this.inputs = inputs;
        }

        private ModelImage crop(Tile tile) {
            if (!tile.sourceId().equals(sourceId)) {
                close();
                InputImage input = inputs.stream().filter(image -> tile.sourceId().equals(image.sourceId()))
                        .findFirst().orElseThrow();
                decoded = BatchOcrImageSupport.decode(input);
                sourceId = tile.sourceId();
            }
            return BatchOcrImageSupport.crop(decoded, tile);
        }

        @Override
        public void close() {
            if (decoded != null) {
                decoded.flush();
                decoded = null;
                sourceId = null;
            }
        }
    }

    private Assembly assemble(State state, boolean finalRound) {
        state.checkCancel();
        state.publish("ASSEMBLE");
        String reply = assembler.assemble(state.sources, List.copyOf(state.tiles), finalRound);
        state.checkCancel();
        try {
            return BatchOcrAssemblyValidator.parse(reply, state.sources, state.tiles);
        } catch (IllegalArgumentException exception) {
            state.warnings.add("模型重组结构或来源引用无效，保留原片供人工校对");
            state.finish(true);
            return null;
        }
    }

    private List<Tile> repairs(List<Repair> requests, State state) {
        Map<String, Integer> counts = new HashMap<>();
        List<Tile> tiles = new ArrayList<>();
        for (Repair request : requests) {
            if (state.tiles.size() + tiles.size() >= BatchOcrImageSupport.MAX_TILES) {
                state.warnings.add("整批已达24个图块上限，仍有区域未完成补识别");
                break;
            }
            Source source = state.sources.stream().filter(item -> item.sourceId().equals(request.sourceId()))
                    .findFirst().orElseThrow();
            Tile original = state.tiles.stream().filter(item -> item.tileId().equals(request.tileId()))
                    .findFirst().orElseThrow();
            int index = counts.merge(source.sourceId(), 1, Integer::sum) - 1;
            Tile repair = BatchOcrImageSupport.repair(source, original, request.edge(), index);
            boolean duplicate = tiles.stream().anyMatch(item -> item.sourceId().equals(repair.sourceId())
                    && item.x() == repair.x() && item.y() == repair.y()
                    && item.width() == repair.width() && item.height() == repair.height());
            if (!duplicate) {
                tiles.add(repair);
            }
        }
        return tiles;
    }

    private static final class State {
        private final List<Source> sources;
        private final List<Tile> tiles = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Observer observer;
        private List<Question> questions = List.of();
        private int total;
        private int completed;

        private State(List<Source> sources, Observer observer) {
            this.sources = sources;
            this.observer = observer;
        }

        private void replace(Tile tile, String markdown) {
            int index = tiles.indexOf(tile);
            tiles.set(index, new Tile(tile.tileId(), tile.sourceId(), tile.x(), tile.y(), tile.width(),
                    tile.height(), tile.repair(), markdown));
        }

        private Result result(boolean incomplete) {
            List<String> bounded = warnings.size() <= 20 ? List.copyOf(warnings)
                    : new ArrayList<>(warnings.subList(0, 19));
            if (warnings.size() > 20) {
                bounded.add("另有校对提示，请逐一核对全部原片文字");
            }
            return new Result(questions, sources, tiles, bounded, incomplete || completed < total);
        }

        private void publish(String stage) {
            observer.partial(result(true));
            observer.progress(completed, total, stage);
        }

        private Result finish(boolean incomplete) {
            Result result = result(incomplete || !warnings.isEmpty());
            observer.partial(result);
            return result;
        }

        private Result finishAfterFailure() {
            Result result = result(true);
            try {
                observer.partial(result);
            } catch (RuntimeException ignored) {
                // Observer 故障不能遮盖原始错误，也不能令已收束分片从返回的部分结果消失。
            }
            return result;
        }

        private boolean cancelRequested() {
            return Thread.currentThread().isInterrupted() || observer.shouldCancel();
        }

        private void checkCancel() {
            if (cancelRequested()) {
                cancel();
            }
        }

        private void cancel() {
            warnings.add("批量识别已取消，未完成部分请人工校对或重新识别");
            throw new BatchOcrCancelledException(finishAfterFailure());
        }
    }
}
