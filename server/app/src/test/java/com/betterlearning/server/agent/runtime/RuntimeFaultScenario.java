package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.MultiAgentModels.Attempt;
import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.RequestDetail;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.google.protobuf.Duration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.RegisterNamespaceRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/** Isolated business DB, Temporal namespace and child JVMs for durable fault tests. */
public final class RuntimeFaultScenario implements AutoCloseable {
    private static final long HTTP_WAIT_SECONDS = 120;
    private static final long READY_WAIT_SECONDS = 60;
    private final PersistentTemporalFixture temporal;
    private final RuntimeBusinessFixture business;
    private final RuntimeBusinessFixture.Seed seed;
    private final String namespace;
    private final String taskQueue;
    private final WorkflowRuntimeProperties properties;
    private WorkflowServiceStubs service;
    private WorkflowClient client;
    private TemporalRunGateway gateway;
    private WorkflowOutboxDispatcher dispatcher;
    private final HttpServer provider;
    private final ExecutorService providerThreads;
    private final Path appDirectory;
    private final Path logDirectory;
    private final Set<Process> children = ConcurrentHashMap.newKeySet();
    private final Map<Process, ChildProcess> processTraces = new ConcurrentHashMap<>();
    private final Set<Process> forcedKills = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> roleCalls = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> barriers = new ConcurrentHashMap<>();
    private final AtomicInteger totalCalls = new AtomicInteger();
    private final AtomicInteger threadSequence = new AtomicInteger();
    private final CountDownLatch firstCall = new CountDownLatch(1);
    private final CountDownLatch barrierRelease = new CountDownLatch(1);
    private final AtomicBoolean callsBlocked = new AtomicBoolean();
    private volatile CountDownLatch callRelease = new CountDownLatch(1);
    private volatile Process worker;

    public RuntimeFaultScenario(PersistentTemporalFixture temporal) {
        this.temporal = temporal;
        business = RuntimeBusinessFixture.start();
        ExecutorService startedThreads = null;
        HttpServer startedProvider = null;
        try {
            seed = business.createQueuedRun();
            namespace = "p2-" + UUID.randomUUID();
            taskQueue = "p2-fault-" + UUID.randomUUID();
            temporal.service().blockingStub().registerNamespace(RegisterNamespaceRequest.newBuilder()
                    .setNamespace(namespace)
                    .setWorkflowExecutionRetentionPeriod(Duration.newBuilder()
                    .setSeconds(TimeUnit.DAYS.toSeconds(7)).build())
                    .build());
            properties = new WorkflowRuntimeProperties();
            properties.setEnabled(true);
            properties.setTemporalTarget(temporal.target());
            properties.setNamespace(namespace);
            properties.setTaskQueue(taskQueue);
            properties.setModelTimeout(java.time.Duration.ofSeconds(30));
            refreshTemporalConnection();
            appDirectory = findAppDirectory();
            logDirectory = appDirectory.resolve("target/p2-fault-logs").resolve(namespace);
            Files.createDirectories(logDirectory);
            startedThreads = Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "p2-fault-http-" + threadSequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            startedProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            startedProvider.setExecutor(startedThreads);
            startedProvider.createContext("/call", this::handleCall);
            startedProvider.createContext("/barrier", this::handleBarrier);
            startedProvider.start();
            providerThreads = startedThreads;
            provider = startedProvider;
        } catch (IOException | RuntimeException | Error error) {
            HttpServer failedProvider = startedProvider;
            ExecutorService failedThreads = startedThreads;
            if (failedProvider != null) {
                tryCleanup(error, () -> failedProvider.stop(0));
            }
            if (failedThreads != null) {
                tryCleanup(error, failedThreads::shutdownNow);
            }
            if (service != null) {
                tryCleanup(error, service::shutdown);
            }
            tryCleanup(error, business::close);
            throw new IllegalStateException("P2 fault fixture setup failed", error);
        }
    }

    public RuntimeBusinessFixture business() {
        return business;
    }

    public RuntimeBusinessFixture.Seed seed() {
        return seed;
    }

    public WorkflowClient client() {
        return client;
    }

    public String namespace() {
        return namespace;
    }

    public TemporalRunGateway gateway() {
        return gateway;
    }

    /** Reconnect this namespace after the shared fixture restarts its persistent Temporal Server. */
    public void refreshTemporalConnection() {
        WorkflowServiceStubs previous = service;
        WorkflowServiceStubs replacement = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporal.target()).build());
        try {
            WorkflowClient newClient = WorkflowClient.newInstance(replacement, WorkflowClientOptions.newBuilder()
                    .setNamespace(namespace).build());
            properties.setTemporalTarget(temporal.target());
            TemporalRunGateway newGateway = new TemporalRunGateway(newClient, properties);
            WorkflowOutboxDispatcher newDispatcher = new WorkflowOutboxDispatcher(business.ledger(),
                    newGateway, Clock.systemUTC(), 50);
            service = replacement;
            client = newClient;
            gateway = newGateway;
            dispatcher = newDispatcher;
        } catch (RuntimeException | Error error) {
            tryCleanup(error, replacement::shutdown);
            throw error;
        }
        if (previous != null) {
            previous.shutdown();
        }
    }

    public void dispatch() {
        dispatcher.run();
    }

    public Process startWorker(String mode) {
        if (workerAlive()) {
            throw new IllegalStateException("P2 worker is already running");
        }
        worker = startChild(RuntimeFaultWorkerMain.class, "worker", mode, false, "P2_WORKER_READY");
        return worker;
    }

    public void killWorker() {
        Process current = worker;
        if (current != null) {
            kill(current);
            worker = null;
        }
    }

    public Process startDispatcher(boolean dispatchNow) {
        return startChild(RuntimeFaultDispatcherMain.class, "dispatcher", "NONE", dispatchNow,
                "P2_DISPATCHER_READY");
    }

    public void kill(Process process) {
        if (process == null || !children.contains(process)) {
            return;
        }
        process.destroyForcibly();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw new AssertionError("P2 child did not exit after forced termination");
            }
            forcedKills.add(process);
            children.remove(process);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while stopping P2 child");
        }
    }

    public long workerPid() {
        return worker == null ? -1 : worker.pid();
    }

    public boolean workerAlive() {
        return worker != null && worker.isAlive();
    }

    public void blockCalls() {
        callRelease = new CountDownLatch(1);
        callsBlocked.set(true);
    }

    public void releaseCalls() {
        callsBlocked.set(false);
        callRelease.countDown();
    }

    public void awaitCall() {
        await(firstCall, 15, "P2 provider call not observed");
    }

    public void awaitBarrier(String point) {
        await(barriers.computeIfAbsent(point, ignored -> new CountDownLatch(1)), 20,
                "P2 barrier not observed: " + point);
    }

    public void releaseBarriers() {
        barrierRelease.countDown();
    }

    public int totalCalls() {
        return totalCalls.get();
    }

    public int calls(String role) {
        AtomicInteger count = roleCalls.get(role);
        return count == null ? 0 : count.get();
    }

    /** 仅导出合成 Temporal history 和白名单账本/进程字段，不序列化正文或连接信息。 */
    public Path writeEvidence(String caseName) throws IOException {
        return writeEvidence(caseName, seed.request().currentRunId());
    }

    public Path writeEvidence(String caseName, String runId) throws IOException {
        if (caseName == null || !caseName.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("P2 evidence caseName must be a short safe identifier");
        }
        String history = client.fetchHistory(runId).toJson(true);
        RequestDetail detail = business.ledger().detail(seed.accountId(), seed.questionId(),
                seed.request().requestId());
        var recordedRun = detail.runs().stream().filter(run -> runId.equals(run.runId())).findFirst().orElseThrow();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("caseName", caseName);
        metadata.put("namespace", namespace);
        metadata.put("runId", runId);
        metadata.put("accountId", seed.accountId());
        metadata.put("workerPid", workerPid());
        metadata.put("processes", processEvidence());
        metadata.put("totalCalls", totalCalls());
        metadata.put("callCountScope", "scenario including explicit retry runs");
        metadata.put("runModelCalls", business.ledger().execution(seed.accountId(), runId).modelCalls());
        Map<String, Integer> counts = new LinkedHashMap<>();
        roleCalls.forEach((role, count) -> counts.put(role, count.get()));
        metadata.put("roleCalls", counts);
        metadata.put("runStatus", recordedRun.status().name());
        metadata.put("runReason", recordedRun.reason());
        metadata.put("attempts", attemptEvidence(recordedRun.attempts()));
        Path historyPath = logDirectory.resolve(caseName + "-history.json");
        Path metadataPath = logDirectory.resolve(caseName + "-metadata.json");
        Files.writeString(historyPath, history, StandardCharsets.UTF_8);
        Files.writeString(metadataPath, JsonMapper.builder().build().writeValueAsString(metadata),
                StandardCharsets.UTF_8);
        return metadataPath;
    }

    private List<Map<String, Object>> processEvidence() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ChildProcess trace : processTraces.values()) {
            Process process = trace.process();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", trace.kind());
            entry.put("mode", trace.mode());
            entry.put("pid", process.pid());
            entry.put("alive", process.isAlive());
            entry.put("forcedKill", forcedKills.contains(process));
            entry.put("exitValue", exitValue(process));
            entries.add(entry);
        }
        entries.sort((left, right) -> Long.compare((Long) left.get("pid"), (Long) right.get("pid")));
        return entries;
    }

    private static Integer exitValue(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException stillRunning) {
            return null;
        }
    }

    private List<Map<String, Object>> attemptEvidence(List<Attempt> attempts) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Attempt attempt : attempts) {
            Map<String, Object> position = business.jdbc().queryForMap(
                    "SELECT role, sequence FROM ma_step WHERE step_id = ?", attempt.stepId());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("attemptId", attempt.attemptId());
            entry.put("stepId", attempt.stepId());
            entry.put("attemptNo", attempt.attemptNo());
            entry.put("role", position.get("role"));
            entry.put("sequence", position.get("sequence"));
            entry.put("status", attempt.status());
            entry.put("fence", attempt.fence());
            entry.put("providerCallState", attempt.providerCallState());
            entries.add(entry);
        }
        return entries;
    }

    public Execution awaitStatus(Status expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        RuntimeException last = null;
        do {
            try {
                Execution execution = business.ledger().execution(seed.accountId(), seed.request().currentRunId());
                if (execution.status() == expected) {
                    return execution;
                }
            } catch (RuntimeException error) {
                last = error;
            }
            pause();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("P2 run did not reach expected status " + expected, last);
    }

    public void awaitWorkflowCompletion() {
        awaitWorkflowCompletion(seed.request().currentRunId());
    }

    public void awaitWorkflowCompletion(String runId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        do {
            try {
                var response = service.blockingStub().describeWorkflowExecution(
                        DescribeWorkflowExecutionRequest.newBuilder().setNamespace(namespace)
                        .setExecution(WorkflowExecution.newBuilder().setWorkflowId(runId).build())
                        .build());
                WorkflowExecutionStatus status = response.getWorkflowExecutionInfo().getStatus();
                if (status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
                        && status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_UNSPECIFIED) {
                    if (status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED) {
                        throw new AssertionError("P2 workflow unexpectedly closed as " + status);
                    }
                    return;
                }
            } catch (RuntimeException notYetVisible) {
                // Start and completion can race this read; retry only until the bounded deadline.
            }
            pause();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("P2 workflow did not complete within 45 seconds");
    }

    private Process startChild(Class<?> mainClass, String kind, String mode, boolean dispatchNow,
            String readyMarker) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        if (classpath == null || classpath.isBlank()) {
            throw new AssertionError("P2 child test classpath unavailable");
        }
        Path log = logDirectory.resolve(kind + "-" + UUID.randomUUID() + ".log");
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath, mainClass.getName());
        builder.directory(appDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("SPRING_DATASOURCE_URL", business.databaseUrl());
        environment.put("SPRING_DATASOURCE_USERNAME", business.databaseUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", business.databasePassword());
        environment.put("APP_FILES_ROOT", business.filesRoot().toString());
        environment.put("APP_AGENT_RETENTION_DELETE_LOG_ROOT", business.journalRoot().toString());
        environment.put("APP_AGENT_RETENTION_INITIALIZE_JOURNAL", "false");
        environment.put("APP_AGENT_RETENTION_SWEEP_ENABLED", "false");
        environment.put("APP_MULTI_AGENT_ROLLOUT_MODE", "V2");
        environment.put("APP_AGENT_EMBEDDING_ENABLED", "false");
        environment.put("APP_AGENT_WORKFLOW_TEMPORAL_TARGET", temporal.target());
        environment.put("APP_AGENT_WORKFLOW_NAMESPACE", namespace);
        environment.put("APP_AGENT_WORKFLOW_TASK_QUEUE", taskQueue);
        environment.put("P2_PROVIDER_URL", "http://127.0.0.1:" + provider.getAddress().getPort());
        environment.put("P2_FAULT_MODE", mode);
        environment.put("P2_DISPATCH_NOW", Boolean.toString(dispatchNow));
        try {
            Process child = builder.start();
            children.add(child);
            processTraces.put(child, new ChildProcess(child, kind, mode));
            awaitReady(child, log, readyMarker, "worker".equals(kind));
            return child;
        } catch (IOException error) {
            throw new AssertionError("P2 child could not start; log: " + log);
        }
    }

    private void awaitReady(Process process, Path log, String marker, boolean requireAlive) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_WAIT_SECONDS);
        do {
            try {
                if (Files.exists(log) && Files.readString(log, StandardCharsets.UTF_8).contains(marker)) {
                    if (requireAlive && !process.isAlive()) {
                        throw new AssertionError("P2 worker exited after READY; log: " + log);
                    }
                    return;
                }
            } catch (IOException error) {
                // A child can create and flush its log while this read is in flight.
            }
            if (!process.isAlive()) {
                throw new AssertionError("P2 child exited before READY; log: " + log);
            }
            pause();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("P2 child did not report READY; log: " + log);
    }

    private void handleCall(HttpExchange exchange) throws IOException {
        String role = query(exchange, "role");
        if (role == null || role.isBlank()) {
            respond(exchange, 400, "missing role");
            return;
        }
        totalCalls.incrementAndGet();
        roleCalls.computeIfAbsent(role, ignored -> new AtomicInteger()).incrementAndGet();
        firstCall.countDown();
        CountDownLatch release = callRelease;
        if (callsBlocked.get() && !waitHttp(release)) {
            respond(exchange, 504, "provider test timeout");
            return;
        }
        respond(exchange, 200, role + "-output");
    }

    private void handleBarrier(HttpExchange exchange) throws IOException {
        String point = query(exchange, "point");
        if (point == null || point.isBlank()) {
            respond(exchange, 400, "missing point");
            return;
        }
        barriers.computeIfAbsent(point, ignored -> new CountDownLatch(1)).countDown();
        if (!waitHttp(barrierRelease)) {
            respond(exchange, 504, "barrier test timeout");
            return;
        }
        respond(exchange, 200, "released");
    }

    private static boolean waitHttp(CountDownLatch latch) {
        try {
            return latch.await(HTTP_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String query(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(key)) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Path findAppDirectory() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.exists(current.resolve("pom.xml")) && current.endsWith("app")) {
            return current;
        }
        Path nested = current.resolve("server/app");
        if (Files.exists(nested.resolve("pom.xml"))) {
            return nested;
        }
        throw new AssertionError("P2 app test working directory unavailable");
    }

    private static void await(CountDownLatch latch, long seconds, String message) {
        try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("P2 wait interrupted");
        }
    }

    private static void pause() {
        try {
            TimeUnit.MILLISECONDS.sleep(100);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("P2 wait interrupted");
        }
    }

    @Override
    public void close() {
        RuntimeException cleanupError = new IllegalStateException("P2 fault fixture cleanup failed");
        tryCleanup(cleanupError, this::releaseCalls);
        tryCleanup(cleanupError, this::releaseBarriers);
        for (Process process : new ArrayList<>(children)) {
            tryCleanup(cleanupError, () -> kill(process));
        }
        tryCleanup(cleanupError, () -> provider.stop(0));
        tryCleanup(cleanupError, providerThreads::shutdownNow);
        tryCleanup(cleanupError, service::shutdown);
        tryCleanup(cleanupError, business::close);
        if (cleanupError.getSuppressed().length > 0) {
            throw cleanupError;
        }
    }

    private static void tryCleanup(Throwable original, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error error) {
            original.addSuppressed(error);
        }
    }

    private record ChildProcess(Process process, String kind, String mode) { }
}
