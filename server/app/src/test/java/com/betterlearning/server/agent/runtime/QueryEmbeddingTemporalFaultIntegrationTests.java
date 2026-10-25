package com.betterlearning.server.agent.runtime;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real pgvector ledger + persistent Temporal; only generation and embedding HTTP provider are synthetic. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(240)
class QueryEmbeddingTemporalFaultIntegrationTests {
    private PersistentTemporalFixture temporal;

    @BeforeAll
    void startTemporal() {
        temporal = PersistentTemporalFixture.start();
    }

    @AfterAll
    void stopTemporal() {
        if (temporal != null) {
            temporal.close();
        }
    }

    @Test
    void killAfterEmbeddingRequestBeforeConfirmationFailsUnknownWithoutResend() throws Exception {
        try (Scenario scenario = new Scenario(temporal, true)) {
            scenario.startWorker(false);
            scenario.dispatch();
            scenario.awaitEmbeddingRequest();
            assertEquals("CALL_INTENT", scenario.queryState());
            assertEquals("NOT_SENT", scenario.retrievalAttemptState());
            assertEquals(1, scenario.embeddingRequests());
            long oldPid = scenario.workerPid();
            scenario.killWorker();
            scenario.releaseEmbedding();
            scenario.startWorker(false);
            assertNotEquals(oldPid, scenario.workerPid());
            scenario.awaitStatus(Status.FAILED);
            scenario.awaitWorkflowCompletion();
            assertEquals("OUTCOME_UNKNOWN", scenario.runReason());
            assertEquals("CALL_INTENT", scenario.queryState());
            assertEquals(1, scenario.embeddingRequests());
            assertEquals(0, scenario.resultCount());
        }
    }

    @Test
    void killAfterVectorConfirmationBeforeStepConfirmationReusesVector() throws Exception {
        try (Scenario scenario = new Scenario(temporal, false)) {
            scenario.startWorker(true);
            scenario.dispatch();
            scenario.awaitQueryConfirmedBarrier();
            assertEquals("CONFIRMED", scenario.queryState());
            assertEquals("NOT_SENT", scenario.retrievalAttemptState());
            assertEquals(1, scenario.embeddingRequests());
            long oldPid = scenario.workerPid();
            scenario.killWorker();
            scenario.releaseBarrier();
            scenario.startWorker(false);
            assertNotEquals(oldPid, scenario.workerPid());
            scenario.awaitStatus(Status.SUCCEEDED);
            scenario.awaitWorkflowCompletion();
            assertEquals("CONFIRMED", scenario.queryState());
            assertEquals("CONFIRMED", scenario.retrievalAttemptState());
            assertEquals(1, scenario.embeddingRequests());
            assertEquals(1, scenario.resultCount());
            assertEquals(4, scenario.modelCalls());
        }
    }

    private static final class Scenario implements AutoCloseable {
        private static final String MODEL = "local-query-fault";
        private static final String QUERY_TEXT = "用配方法求解二次方程";
        private final PersistentTemporalFixture temporal;
        private final RuntimeBusinessFixture business;
        private final RuntimeBusinessFixture.Seed seed;
        private final String namespace = "query-fault-" + UUID.randomUUID();
        private final String taskQueue = "query-fault-task-" + UUID.randomUUID();
        private final Path logDirectory;
        private final WorkflowServiceStubs service;
        private final WorkflowOutboxDispatcher dispatcher;
        private final HttpServer stub;
        private final ExecutorService stubThreads;
        private final AtomicInteger embeddingRequests = new AtomicInteger();
        private final CountDownLatch embeddingReceived = new CountDownLatch(1);
        private final CountDownLatch embeddingRelease = new CountDownLatch(1);
        private final CountDownLatch queryConfirmed = new CountDownLatch(1);
        private final CountDownLatch barrierRelease = new CountDownLatch(1);
        private final boolean holdEmbedding;
        private final ArrayList<Process> children = new ArrayList<>();
        private Process worker;
        private long initialPid = -1;
        private long replacementPid = -1;
        private long killedPid = -1;
        private Integer killedExitCode;

        Scenario(PersistentTemporalFixture temporal, boolean holdEmbedding) throws IOException {
            this.temporal = temporal;
            this.holdEmbedding = holdEmbedding;
            RuntimeBusinessFixture startedBusiness = null;
            WorkflowServiceStubs startedService = null;
            ExecutorService startedThreads = null;
            HttpServer startedStub = null;
            try {
                startedBusiness = RuntimeBusinessFixture.start();
                RuntimeBusinessFixture.Seed startedSeed = startedBusiness.createQueuedRun();
                Path startedLogDirectory = appDirectory().resolve("target/query-embedding-fault-logs")
                        .resolve(namespace);
                Files.createDirectories(startedLogDirectory);
                temporal.service().blockingStub().registerNamespace(RegisterNamespaceRequest.newBuilder()
                        .setNamespace(namespace)
                        .setWorkflowExecutionRetentionPeriod(Duration.newBuilder()
                        .setSeconds(TimeUnit.DAYS.toSeconds(7)).build()).build());
                startedService = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(temporal.target()).build());
                WorkflowClient client = WorkflowClient.newInstance(startedService, WorkflowClientOptions.newBuilder()
                        .setNamespace(namespace).build());
                WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
                properties.setEnabled(true);
                properties.setTemporalTarget(temporal.target());
                properties.setNamespace(namespace);
                properties.setTaskQueue(taskQueue);
                properties.setModelTimeout(java.time.Duration.ofSeconds(30));
                WorkflowOutboxDispatcher startedDispatcher = new WorkflowOutboxDispatcher(startedBusiness.ledger(),
                        new TemporalRunGateway(client, properties), Clock.systemUTC(), 50);
                startedThreads = Executors.newCachedThreadPool(task -> {
                    Thread thread = new Thread(task, "query-fault-http");
                    thread.setDaemon(true);
                    return thread;
                });
                startedStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                startedStub.setExecutor(startedThreads);
                startedStub.createContext("/embeddings", this::handleEmbedding);
                startedStub.createContext("/barrier", this::handleBarrier);
                startedStub.start();
                business = startedBusiness;
                seed = startedSeed;
                logDirectory = startedLogDirectory;
                service = startedService;
                dispatcher = startedDispatcher;
                stubThreads = startedThreads;
                stub = startedStub;
            } catch (IOException | RuntimeException | Error error) {
                if (startedStub != null) {
                    startedStub.stop(0);
                }
                if (startedThreads != null) {
                    startedThreads.shutdownNow();
                }
                if (startedService != null) {
                    startedService.shutdown();
                }
                if (startedBusiness != null) {
                    startedBusiness.close();
                }
                throw error;
            }
        }

        void dispatch() {
            dispatcher.run();
        }

        void startWorker(boolean pauseAfterQuery) throws IOException {
            if (worker != null && worker.isAlive()) {
                throw new IllegalStateException("query fault worker already active");
            }
            String classpath = System.getProperty("surefire.test.class.path");
            if (classpath == null || classpath.isBlank()) {
                classpath = System.getProperty("java.class.path");
            }
            Path log = logDirectory.resolve("worker-" + UUID.randomUUID() + ".log");
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
                    .toString(), "-cp", classpath, QueryEmbeddingFaultWorkerMain.class.getName());
            builder.directory(appDirectory().toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            Map<String, String> env = builder.environment();
            env.clear();
            env.put("SPRING_DATASOURCE_URL", business.databaseUrl());
            env.put("SPRING_DATASOURCE_USERNAME", business.databaseUsername());
            env.put("SPRING_DATASOURCE_PASSWORD", business.databasePassword());
            env.put("APP_FILES_ROOT", business.filesRoot().toString());
            env.put("APP_AGENT_RETENTION_DELETE_LOG_ROOT", business.journalRoot().toString());
            env.put("APP_AGENT_RETENTION_INITIALIZE_JOURNAL", "false");
            env.put("APP_AGENT_RETENTION_SWEEP_ENABLED", "false");
            env.put("APP_MULTI_AGENT_ROLLOUT_MODE", "V2");
            env.put("APP_AGENT_WORKFLOW_TEMPORAL_TARGET", temporal.target());
            env.put("APP_AGENT_WORKFLOW_NAMESPACE", namespace);
            env.put("APP_AGENT_WORKFLOW_TASK_QUEUE", taskQueue);
            env.put("APP_AGENT_WORKFLOW_MODEL_TIMEOUT", "PT30S");
            env.put("APP_AGENT_EMBEDDING_ENABLED", "true");
            env.put("APP_AGENT_EMBEDDING_BASE_URL", baseUrl());
            env.put("APP_AGENT_EMBEDDING_API_KEY", "local-fixture-only");
            env.put("APP_AGENT_EMBEDDING_MODEL", MODEL);
            env.put("APP_AGENT_EMBEDDING_DIMENSIONS", "2");
            env.put("APP_AGENT_EMBEDDING_TIMEOUT_SECONDS", "30");
            env.put("P2_QUERY_BARRIER_URL", baseUrl() + "/barrier");
            env.put("P2_QUERY_PAUSE_AFTER_CONFIRM", Boolean.toString(pauseAfterQuery));
            worker = builder.start();
            children.add(worker);
            if (initialPid < 0) {
                initialPid = worker.pid();
            } else {
                replacementPid = worker.pid();
            }
            await(() -> {
                if (!worker.isAlive()) {
                    throw new AssertionError("query fault worker exited before READY; log: " + log);
                }
                try {
                    return Files.exists(log) && Files.readString(log, StandardCharsets.UTF_8)
                            .contains("QUERY_WORKER_READY");
                } catch (IOException error) {
                    return false;
                }
            }, 60, "query fault worker not ready; log: " + log);
        }

        void killWorker() throws InterruptedException {
            killedPid = worker.pid();
            worker.destroyForcibly();
            assertTrue(worker.waitFor(10, TimeUnit.SECONDS), "worker did not die");
            killedExitCode = worker.exitValue();
            worker = null;
        }

        long workerPid() {
            return worker.pid();
        }

        void awaitEmbeddingRequest() {
            await(() -> embeddingReceived.getCount() == 0, 30, "embedding HTTP request not received");
        }

        void awaitQueryConfirmedBarrier() {
            await(() -> queryConfirmed.getCount() == 0, 30, "confirmed query barrier not reached");
        }

        void releaseEmbedding() {
            embeddingRelease.countDown();
        }

        void releaseBarrier() {
            barrierRelease.countDown();
        }

        int embeddingRequests() {
            return embeddingRequests.get();
        }

        String queryState() {
            return business.jdbc().queryForObject("SELECT q.call_state FROM ma_query_embedding q "
                    + "JOIN ma_step s ON s.step_id=q.step_id WHERE s.run_id=? AND s.role='RETRIEVE'",
                    String.class, runId());
        }

        String retrievalAttemptState() {
            return business.jdbc().queryForObject("SELECT a.provider_call_state FROM ma_attempt a "
                    + "JOIN ma_step s ON s.step_id=a.step_id WHERE s.run_id=? AND s.role='RETRIEVE'",
                    String.class, runId());
        }

        int resultCount() {
            return business.jdbc().queryForObject("SELECT COUNT(*) FROM ma_result WHERE run_id=?",
                    Integer.class, runId());
        }

        int modelCalls() {
            return business.ledger().execution(seed.accountId(), runId()).modelCalls();
        }

        String runReason() {
            return business.ledger().detail(seed.accountId(), seed.questionId(),
                    seed.request().requestId()).run().reason();
        }

        void awaitStatus(Status status) {
            await(() -> business.ledger().execution(seed.accountId(), runId()).status() == status,
                    90, "run did not reach " + status);
        }

        void awaitWorkflowCompletion() {
            await(() -> {
                try {
                    var response = service.blockingStub().describeWorkflowExecution(
                            DescribeWorkflowExecutionRequest.newBuilder().setNamespace(namespace)
                            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(runId()).build())
                            .build());
                    return response.getWorkflowExecutionInfo().getStatus()
                            == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED;
                } catch (RuntimeException notVisibleYet) {
                    return false;
                }
            }, 90, "workflow did not complete");
        }

        private String runId() {
            return seed.request().currentRunId();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + stub.getAddress().getPort();
        }

        private void handleEmbedding(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    respond(exchange, 405, "method not allowed");
                    return;
                }
                byte[] body = exchange.getRequestBody().readAllBytes();
                var inputs = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("input");
                // The P4 index scheduler may see synthetic source outbox rows; its calls are outside this
                // query-accounting experiment and receive no synthetic vector or counted query response.
                if (!inputs.isArray() || inputs.size() != 1 || !QUERY_TEXT.equals(inputs.get(0).asText())) {
                    respond(exchange, 503, "fixture only accepts the query embedding input");
                    return;
                }
                embeddingRequests.incrementAndGet();
                embeddingReceived.countDown();
                if (holdEmbedding && !waitFor(embeddingRelease)) {
                    respond(exchange, 504, "fixture timeout");
                    return;
                }
                respond(exchange, 200, "{\"model\":\"" + MODEL + "\",\"usage\":{"
                        + "\"prompt_tokens\":2,\"total_tokens\":2},\"data\":[{"
                        + "\"index\":0,\"embedding\":[1,0]}]}");
            } finally {
                exchange.close();
            }
        }

        private void handleBarrier(HttpExchange exchange) throws IOException {
            try {
                queryConfirmed.countDown();
                if (!waitFor(barrierRelease)) {
                    respond(exchange, 504, "fixture timeout");
                    return;
                }
                respond(exchange, 200, "released");
            } finally {
                exchange.close();
            }
        }

        private static boolean waitFor(CountDownLatch latch) {
            try {
                return latch.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private static Path appDirectory() {
            Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path nested = cwd.resolve("server/app");
            if (Files.exists(nested.resolve("pom.xml"))) {
                return nested;
            }
            if (cwd.endsWith("app") && Files.exists(cwd.resolve("pom.xml"))) {
                return cwd;
            }
            throw new AssertionError("app test working directory unavailable");
        }

        private static void await(BooleanSupplier condition, int seconds, String message) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            do {
                if (condition.getAsBoolean()) {
                    return;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("query fault wait interrupted", error);
                }
            } while (System.nanoTime() < deadline);
            throw new AssertionError(message);
        }

        private void writeEvidence() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("evidenceKind", "query-embedding-temporal-worker-kill");
            metadata.put("window", holdEmbedding ? "HTTP_RECEIVED_UNCONFIRMED" : "QUERY_CONFIRMED_STEP_NOT_SENT");
            metadata.put("generation", "synthetic_four_roles");
            metadata.put("embeddingProvider", "local_counting_http_stub");
            metadata.put("namespace", namespace);
            metadata.put("runId", runId());
            metadata.put("initialWorkerPid", initialPid);
            metadata.put("killedWorkerPid", killedPid);
            metadata.put("killedWorkerExitCode", killedExitCode);
            metadata.put("replacementWorkerPid", replacementPid);
            metadata.put("queryEmbeddingHttpRequests", embeddingRequests());
            try {
                var execution = business.ledger().execution(seed.accountId(), runId());
                metadata.put("finalRunStatus", execution.status().name());
                metadata.put("modelCalls", execution.modelCalls());
                metadata.put("finalRunReason", runReason());
                metadata.put("resultCount", resultCount());
            } catch (RuntimeException unavailable) {
                metadata.put("finalRunStatus", "UNAVAILABLE");
            }
            try {
                var rows = business.jdbc().queryForList("SELECT q.call_state,q.embedding_requests,"
                        + "q.embedding_tokens FROM ma_query_embedding q JOIN ma_step s ON s.step_id=q.step_id "
                        + "WHERE s.run_id=? AND s.role='RETRIEVE'", runId());
                if (rows.size() == 1) {
                    metadata.put("queryCallState", rows.get(0).get("call_state"));
                    metadata.put("queryLedgerRequests", rows.get(0).get("embedding_requests"));
                    metadata.put("queryLedgerTokens", rows.get(0).get("embedding_tokens"));
                }
                var attempts = business.jdbc().queryForList("SELECT a.provider_call_state FROM ma_attempt a "
                        + "JOIN ma_step s ON s.step_id=a.step_id WHERE s.run_id=? AND s.role='RETRIEVE'",
                        runId());
                if (attempts.size() == 1) {
                    metadata.put("retrievalAttemptState", attempts.get(0).get("provider_call_state"));
                }
            } catch (RuntimeException unavailable) {
                metadata.put("ledgerRead", "UNAVAILABLE");
            }
            try {
                Files.writeString(logDirectory.resolve("metadata.json"),
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata),
                        StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new IllegalStateException("query fault evidence write failed", error);
            }
        }

        @Override
        public void close() {
            RuntimeException cleanupError = new IllegalStateException("query fault fixture cleanup failed");
            releaseEmbedding();
            releaseBarrier();
            for (Process child : children) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    try {
                        if (!child.waitFor(10, TimeUnit.SECONDS)) {
                            cleanupError.addSuppressed(new IllegalStateException("query fault child did not exit"));
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        cleanupError.addSuppressed(error);
                    }
                }
            }
            tryCleanup(cleanupError, this::writeEvidence);
            tryCleanup(cleanupError, () -> stub.stop(0));
            tryCleanup(cleanupError, stubThreads::shutdownNow);
            tryCleanup(cleanupError, service::shutdown);
            tryCleanup(cleanupError, business::close);
            if (cleanupError.getSuppressed().length > 0) {
                throw cleanupError;
            }
        }

        private static void tryCleanup(Throwable original, Runnable action) {
            try {
                action.run();
            } catch (RuntimeException | Error error) {
                original.addSuppressed(error);
            }
        }
    }
}
