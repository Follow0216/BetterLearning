package com.betterlearning.server.agent.batch;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import com.betterlearning.server.agent.batch.BatchOcrStore.Snapshot;
import com.betterlearning.server.agent.model.BatchOcrModels.InputImage;
import com.betterlearning.server.agent.model.BatchOcrModels.Result;
import com.betterlearning.server.agent.service.BatchOcrEngine;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 本地伪存储+真实Engine，断言slot/租约只在底层调用真实退出后释放。 */
class BatchOcrServiceTests {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeWaitsForIssuedCallsAndReleasesRunningAndQueuedLeasesAndInputSlots(boolean initiallyInterrupted)
            throws Exception {
        MemoryStore store = new MemoryStore();
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean modelInterrupted = new AtomicBoolean();
        BatchOcrService service = new BatchOcrService(store, new BatchOcrEngine(image -> {
            calls.incrementAndGet();
            active.incrementAndGet();
            entered.countDown();
            try {
                uninterruptible(release, modelInterrupted);
                return image.position();
            } finally {
                active.decrementAndGet();
            }
        }, (sources, tiles, last) -> {
            throw new AssertionError("关闭后不能组装");
        }));
        CompletableFuture<Void> closed = null;
        try {
            service.submit(1, "running", "one", () -> List.of(image(9000)));
            await(entered);
            service.submit(2, "queued", "two", () -> List.of(image(100)));
            assertThat(slots(service)).isZero();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            closed = completion;
            Thread closer = new Thread(() -> {
                if (initiallyInterrupted) {
                    Thread.currentThread().interrupt();
                }
                try {
                    service.close();
                    assertThat(Thread.currentThread().isInterrupted()).isEqualTo(initiallyInterrupted);
                    // 重复close仍为直接返回，不得吞掉已恢复的调用者中断标记。
                    service.close();
                    assertThat(Thread.currentThread().isInterrupted()).isEqualTo(initiallyInterrupted);
                    completion.complete(null);
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            }, "controlled-batch-closer");
            closer.start();
            org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
                    "UNKNOWN".equals(store.find(2, "queued").status()) && slots(service) == 1);
            assertThat(closed).isNotDone();
            assertThat(store.leased(1, "running")).isTrue();
            assertThat(store.leased(2, "queued")).isFalse();
            assertThat(store.find(1, "running").status()).isEqualTo("RUNNING");
            assertThat(slots(service)).isEqualTo(1);
            assertThat(active.get()).isEqualTo(3);
            assertThat(heartbeats(service).isShutdown()).isFalse();
            release.countDown();
            closed.get(5, TimeUnit.SECONDS);
            assertThat(active.get()).isZero();
            assertThat(calls.get()).isEqualTo(3);
            assertThat(modelInterrupted.get()).isFalse();
            assertThat(store.find(1, "running").status()).isEqualTo("UNKNOWN");
            assertThat(store.find(1, "running").result().tiles().subList(0, 3))
                    .allSatisfy(tile -> assertThat(tile.markdown()).isNotBlank());
            assertThat(store.leased(1, "running")).isFalse();
            assertThat(slots(service)).isEqualTo(2);
            assertThat(store.runningStarts.get()).isEqualTo(1);
            assertThat(heartbeats(service).isShutdown()).isTrue();
            assertThat(store.cleanupInterrupted.get()).isFalse();
            assertThat(store.runningOwner.get().isInterrupted()).isTrue();
        } finally {
            release.countDown();
            if (closed != null) {
                closed.get(5, TimeUnit.SECONDS);
            }
            service.close();
        }
    }

    @Test
    void cancelKeepsTerminalSnapshotAndSlotUntilLateCallsActuallyEnd() throws Exception {
        MemoryStore store = new MemoryStore();
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        BatchOcrService service = new BatchOcrService(store, new BatchOcrEngine(image -> {
            entered.countDown();
            uninterruptible(release, new AtomicBoolean());
            return "晚到片段";
        }, (sources, tiles, last) -> {
            throw new AssertionError("取消后不能组装");
        }));
        try {
            service.submit(1, "cancelled", "one", () -> List.of(image(9000)));
            await(entered);
            Snapshot before = store.find(1, "cancelled");
            BatchOcrService.Response cancelled = service.cancel(1, "cancelled");
            assertThat(cancelled.status()).isEqualTo("CANCELLED");
            assertThat(store.leased(1, "cancelled")).isTrue();
            assertThat(slots(service)).isEqualTo(1);
            release.countDown();
            org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
                    !store.leased(1, "cancelled"));
            Snapshot after = store.find(1, "cancelled");
            assertThat(after.status()).isEqualTo("CANCELLED");
            assertThat(after.result()).isEqualTo(before.result());
            assertThat(after.completedTiles()).isEqualTo(before.completedTiles());
            assertThat(slots(service)).isEqualTo(2);
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void closeWhileStartIsWaitingReleasesTheDequeuedQueuedJob() throws Exception {
        MemoryStore store = new MemoryStore();
        store.waitAtStart = true;
        BatchOcrService service = new BatchOcrService(store, noModels());
        try {
            service.submit(1, "waiting", "one", () -> List.of(image(100)));
            await(store.startAttempted);
            service.close();
            assertThat(store.find(1, "waiting").status()).isEqualTo("UNKNOWN");
            assertThat(store.leased(1, "waiting")).isFalse();
            assertThat(slots(service)).isEqualTo(2);
            assertThat(store.runningStarts.get()).isZero();
        } finally {
            service.close();
        }
    }

    @Test
    void loaderReturningAfterCloseDoesNotCreatePersistentBatch() throws Exception {
        MemoryStore store = new MemoryStore();
        BatchOcrService service = new BatchOcrService(store, noModels());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<BatchOcrService.Response> submit = CompletableFuture.supplyAsync(() -> {
            try {
                return service.submit(1, "late-loader", "one", () -> {
                    entered.countDown();
                    uninterruptible(release, new AtomicBoolean());
                    return List.of(image(100));
                });
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        try {
            await(entered);
            service.close();
            release.countDown();
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> submit.get(5, TimeUnit.SECONDS));
            assertThat(error.getCause()).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) error.getCause()).getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(store.find(1, "late-loader")).isNull();
            assertThat(store.created.get()).isZero();
            assertThat(slots(service)).isEqualTo(2);
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void createRacingWithCloseEndsUnknownAndReturnsItsInputSlot() throws Exception {
        MemoryStore store = new MemoryStore();
        store.blockCreate = true;
        BatchOcrService service = new BatchOcrService(store, noModels());
        CompletableFuture<BatchOcrService.Response> submit = CompletableFuture.supplyAsync(() -> {
            try {
                return service.submit(1, "late-create", "one", () -> List.of(image(100)));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        try {
            await(store.createEntered);
            service.close();
            store.releaseCreate.countDown();
            assertThat(submit.get(5, TimeUnit.SECONDS).status()).isEqualTo("UNKNOWN");
            assertThat(store.leased(1, "late-create")).isFalse();
            assertThat(slots(service)).isEqualTo(2);
        } finally {
            store.releaseCreate.countDown();
            service.close();
        }
    }

    private static BatchOcrEngine noModels() {
        return new BatchOcrEngine(image -> {
            throw new AssertionError("此测试不应启动OCR");
        }, (sources, tiles, last) -> {
            throw new AssertionError("此测试不应组装");
        });
    }

    private static java.util.concurrent.ScheduledExecutorService heartbeats(BatchOcrService service)
            throws ReflectiveOperationException {
        var field = BatchOcrService.class.getDeclaredField("heartbeats");
        field.setAccessible(true);
        return (java.util.concurrent.ScheduledExecutorService) field.get(service);
    }

    private static int slots(BatchOcrService service) throws ReflectiveOperationException {
        var field = BatchOcrService.class.getDeclaredField("inputSlots");
        field.setAccessible(true);
        return ((java.util.concurrent.Semaphore) field.get(service)).availablePermits();
    }

    private static InputImage image(int height) throws IOException {
        BufferedImage image = new BufferedImage(60, height, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return new InputImage("image-0", 0, "image/png", output.toByteArray());
        } finally {
            image.flush();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void uninterruptible(CountDownLatch latch, AtomicBoolean interrupted) {
        boolean waiting = true;
        while (waiting) {
            try {
                latch.await();
                waiting = false;
            } catch (InterruptedException exception) {
                interrupted.set(true);
            }
        }
    }

    /** 复制端口的状态/owner/租约围栏；不涉及数据库或Spring上下文。 */
    private static final class MemoryStore implements BatchOcrStore {
        private final Map<String, Snapshot> rows = new HashMap<>();
        private final Map<String, String> leases = new HashMap<>();
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger runningStarts = new AtomicInteger();
        private final AtomicReference<Thread> runningOwner = new AtomicReference<>();
        private final AtomicBoolean cleanupInterrupted = new AtomicBoolean();
        private final CountDownLatch startAttempted = new CountDownLatch(1);
        private final CountDownLatch createEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCreate = new CountDownLatch(1);
        private boolean waitAtStart;
        private boolean blockCreate;

        private static String key(long accountId, String requestId) {
            return accountId + ":" + requestId;
        }

        private synchronized boolean leased(long accountId, String requestId) {
            return leases.containsKey(key(accountId, requestId));
        }

        @Override
        public synchronized Snapshot find(long accountId, String requestId) {
            return rows.get(key(accountId, requestId));
        }

        @Override
        public Created create(long accountId, String requestId, String fingerprint, String owner) {
            if (blockCreate) {
                createEntered.countDown();
                uninterruptible(releaseCreate, new AtomicBoolean());
            }
            synchronized (this) {
                String key = key(accountId, requestId);
                Snapshot row = new Snapshot(requestId, fingerprint, "QUEUED", "QUEUED", 0, 0, null, null, null);
                rows.put(key, row);
                leases.put(key, owner);
                created.incrementAndGet();
                return new Created(row, true);
            }
        }

        @Override
        public synchronized Start start(long accountId, String requestId, String owner) {
            startAttempted.countDown();
            if (waitAtStart) {
                return Start.WAITING;
            }
            Snapshot row = find(accountId, requestId);
            if (!owner.equals(leases.get(key(accountId, requestId))) || !"QUEUED".equals(row.status())) {
                return Start.FENCED;
            }
            runningStarts.incrementAndGet();
            runningOwner.set(Thread.currentThread());
            rows.put(key(accountId, requestId), copy(row, "RUNNING", "VALIDATING", 0, 0, null));
            return Start.STARTED;
        }

        @Override
        public synchronized boolean heartbeat(long accountId, String requestId, String owner) {
            return owner.equals(leases.get(key(accountId, requestId)));
        }

        @Override
        public synchronized boolean progress(long accountId, String requestId, String owner,
                int completed, int total, String stage, Result partial) {
            Snapshot row = find(accountId, requestId);
            if (!owner.equals(leases.get(key(accountId, requestId))) || !"RUNNING".equals(row.status())) {
                return false;
            }
            rows.put(key(accountId, requestId), copy(row, row.status(), stage, completed, total,
                    partial == null ? row.result() : partial));
            return true;
        }

        @Override
        public synchronized void finish(long accountId, String requestId, String owner, String status,
                Result result, String errorCode, String message) {
            rejectInterruptedCleanup();
            Snapshot row = find(accountId, requestId);
            if (!owner.equals(leases.get(key(accountId, requestId))) || !row.active()) {
                return;
            }
            rows.put(key(accountId, requestId), copy(row, status, status, row.completedTiles(), row.totalTiles(),
                    result == null ? row.result() : result));
            leases.remove(key(accountId, requestId));
        }

        @Override
        public synchronized Snapshot cancel(long accountId, String requestId) {
            Snapshot row = find(accountId, requestId);
            if (row.active()) {
                row = copy(row, "CANCELLED", "CANCELLED", row.completedTiles(), row.totalTiles(), row.result());
                rows.put(key(accountId, requestId), row);
            }
            return row;
        }

        @Override
        public synchronized void releaseCancelled(long accountId, String requestId, String owner) {
            rejectInterruptedCleanup();
            Snapshot row = find(accountId, requestId);
            if (owner.equals(leases.get(key(accountId, requestId))) && "CANCELLED".equals(row.status())) {
                leases.remove(key(accountId, requestId));
            }
        }

        private void rejectInterruptedCleanup() {
            if (Thread.currentThread().isInterrupted()) {
                cleanupInterrupted.set(true);
                throw new IllegalStateException("模拟连接池拒绝中断线程获取连接");
            }
        }

        private static Snapshot copy(Snapshot row, String status, String stage, int completed, int total,
                Result result) {
            return new Snapshot(row.requestId(), row.fingerprint(), status, stage,
                    completed, total, result, null, null);
        }
    }
}
