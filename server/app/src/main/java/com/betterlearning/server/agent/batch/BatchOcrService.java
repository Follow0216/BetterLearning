package com.betterlearning.server.agent.batch;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.betterlearning.server.agent.batch.BatchOcrStore.Snapshot;
import com.betterlearning.server.agent.model.BatchOcrCancelledException;
import com.betterlearning.server.agent.model.BatchOcrModels.InputImage;
import com.betterlearning.server.agent.model.BatchOcrModels.Result;
import com.betterlearning.server.agent.model.BatchOcrProcessingException;
import com.betterlearning.server.agent.service.BatchOcrEngine;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

/** 两个有界内存输入槽与一个 worker；重放或查询永远不重新调用模型。 */
@Service
public class BatchOcrService {
    @FunctionalInterface
    public interface ImageLoader {
        List<InputImage> load() throws IOException;
    }

    public record Response(String requestId, String status, String stage, int completedTiles,
            int totalTiles, Result result, String errorCode, String message) {
    }

    private record Key(long accountId, String requestId) {
    }

    private final BatchOcrStore store;
    private final BatchOcrEngine engine;
    private final String owner = UUID.randomUUID().toString();
    private final Semaphore inputSlots = new Semaphore(2);
    private final ConcurrentHashMap<Key, Job> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService heartbeats;

    public BatchOcrService(BatchOcrStore store, BatchOcrEngine engine) {
        this.store = store;
        this.engine = engine;
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "batch-ocr-worker");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "batch-ocr-lease");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeats.scheduleWithFixedDelay(this::heartbeat, 20, 20, TimeUnit.SECONDS);
    }

    public Response submit(long accountId, String requestId, String fingerprint, ImageLoader loader)
            throws IOException {
        Snapshot existing = store.find(accountId, requestId);
        if (existing != null) {
            return replay(existing, fingerprint);
        }
        if (!inputSlots.tryAcquire()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "识别队列已满，请稍后再提交");
        }
        boolean handedOff = false;
        try {
            // 只有占有输入槽的新批次才读取完整字节。重复 POST 只流式计算摘要。
            List<InputImage> images = List.copyOf(loader.load());
            var created = store.create(accountId, requestId, fingerprint, owner);
            if (!created.created()) {
                return replay(created.snapshot(), fingerprint);
            }
            Key key = new Key(accountId, requestId);
            Job job = new Job(key, images);
            jobs.put(key, job);
            try {
                worker.execute(job);
                handedOff = true;
            } catch (RejectedExecutionException exception) {
                jobs.remove(key, job);
                store.finish(accountId, requestId, owner, "FAILED", null,
                        ErrorCode.SERVICE_UNAVAILABLE.getCode(), "识别执行器暂时不可用，请保留原图");
                return response(required(store.find(accountId, requestId)));
            }
            return response(created.snapshot());
        } finally {
            if (!handedOff) {
                inputSlots.release();
            }
        }
    }

    public Response detail(long accountId, String requestId) {
        return response(required(store.find(accountId, requestId)));
    }

    public Response cancel(long accountId, String requestId) {
        Snapshot snapshot = required(store.cancel(accountId, requestId));
        Key key = new Key(accountId, requestId);
        Job job = jobs.get(key);
        if (job != null && "CANCELLED".equals(snapshot.status())) {
            job.cancelled.set(true);
            if (worker.remove(job)) {
                job.release();
            }
        }
        return response(snapshot);
    }

    private Response replay(Snapshot snapshot, String fingerprint) {
        if (!snapshot.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.CONFIRM_REQUEST_CONFLICT, "识别请求 ID 已用于不同的图像内容或顺序");
        }
        return response(snapshot);
    }

    private static Snapshot required(Snapshot snapshot) {
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return snapshot;
    }

    private static Response response(Snapshot snapshot) {
        return new Response(snapshot.requestId(), snapshot.status(), snapshot.stage(), snapshot.completedTiles(),
                snapshot.totalTiles(), snapshot.result(), snapshot.errorCode(), snapshot.message());
    }

    private void heartbeat() {
        for (Job job : jobs.values()) {
            try {
                if (!store.heartbeat(job.key.accountId(), job.key.requestId(), owner)) {
                    job.cancelled.set(true);
                }
            } catch (RuntimeException ignored) {
                // 不把数据库暂时不可用当作模型失败。租约届满后只会 UNKNOWN，不会自动重跑。
            }
        }
    }

    @PreDestroy
    public void close() {
        heartbeats.shutdownNow();
        worker.shutdownNow();
        for (Job job : jobs.values()) {
            job.cancelled.set(true);
            try {
                store.finish(job.key.accountId(), job.key.requestId(), owner, "UNKNOWN", null,
                        ErrorCode.SERVICE_UNAVAILABLE.getCode(), "执行 worker 已停止，请保留原图和已识别内容");
            } catch (RuntimeException ignored) {
                // 数据库故障时由五分钟租约明确标记 UNKNOWN。
            }
        }
    }

    private final class Job implements Runnable, BatchOcrEngine.Observer {
        private final Key key;
        private final List<InputImage> images;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private Result partial;
        private int completed;
        private int total;
        private String stage = "VALIDATING";

        private Job(Key key, List<InputImage> images) {
            this.key = key;
            this.images = images;
        }

        @Override
        public void run() {
            try {
                BatchOcrStore.Start start;
                do {
                    if (shouldCancel()) {
                        return;
                    }
                    start = store.start(key.accountId(), key.requestId(), owner);
                    if (start == BatchOcrStore.Start.WAITING) {
                        TimeUnit.SECONDS.sleep(1);
                    }
                } while (start == BatchOcrStore.Start.WAITING);
                if (start != BatchOcrStore.Start.STARTED || shouldCancel()) {
                    return;
                }
                Result result = engine.process(images, this);
                String status = result.incomplete() ? "INCOMPLETE"
                        : result.questions().isEmpty() ? "EMPTY" : "COMPLETED";
                finish(status, result, null, null);
            } catch (BatchOcrCancelledException exception) {
                // DELETE 已持久化状态；晚到结果不覆盖取消或已过期 UNKNOWN。
            } catch (BatchOcrProcessingException exception) {
                Result result = exception.partialResult();
                finish("FAILED", result,
                        exception.failure().name(), exception.failure().message());
            } catch (IllegalArgumentException exception) {
                finish("FAILED", partial, ErrorCode.INVALID_PARAM.getCode(), "图像内容不合法或超出识别限制");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                finish("UNKNOWN", partial, ErrorCode.SERVICE_UNAVAILABLE.getCode(), "识别执行被中断，请保留原图");
            } catch (RuntimeException exception) {
                finish("UNKNOWN", partial, ErrorCode.INTERNAL_ERROR.getCode(), "识别执行状态未知，请保留原图");
            } finally {
                release();
            }
        }

        @Override
        public boolean shouldCancel() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                return true;
            }
            Snapshot snapshot = store.find(key.accountId(), key.requestId());
            return snapshot == null || !snapshot.active();
        }

        @Override
        public void progress(int completedTiles, int totalTiles, String currentStage) {
            completed = completedTiles;
            total = totalTiles;
            stage = currentStage;
            if (!store.progress(key.accountId(), key.requestId(), owner, completed, total, stage, null)) {
                cancelled.set(true);
            }
        }

        @Override
        public void partial(Result result) {
            partial = result;
            if (!store.progress(key.accountId(), key.requestId(), owner, completed, total, stage, result)) {
                cancelled.set(true);
            }
        }

        private void finish(String status, Result result, String errorCode, String message) {
            try {
                store.finish(key.accountId(), key.requestId(), owner, status, result, errorCode, message);
            } catch (RuntimeException ignored) {
                // 不重复模型或吞掉围栏。恢复只读持久状态；失去 worker 后租约最终变为 UNKNOWN。
            }
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                jobs.remove(key, this);
                inputSlots.release();
                try {
                    store.releaseCancelled(key.accountId(), key.requestId(), owner);
                } catch (RuntimeException ignored) {
                    // 取消状态已经落库；未释放租约仅会暂时占用队列，过期后自然释放。
                }
            }
        }
    }
}
