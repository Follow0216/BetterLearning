package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.EvidenceRetriever;
import com.betterlearning.server.multiagent.MultiAgentModels.PipelineResult;
import com.betterlearning.server.multiagent.MultiAgentModels.RetrievalQuery;
import com.betterlearning.server.multiagent.MultiAgentModels.Role;
import com.betterlearning.server.multiagent.MultiAgentModels.Snapshot;
import com.betterlearning.server.multiagent.MultiAgentModels.StepInvocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StepOutput;
import com.betterlearning.server.multiagent.RolePipeline;
import com.betterlearning.server.multiagent.SnapshotPort;
import com.betterlearning.server.multiagent.TutorLedger;
import com.betterlearning.server.agent.retrieval.PersonalEvidenceRetriever;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.retrieval.MybatisRetrievalIndexStore;
import com.betterlearning.worker.AgentWorkerApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/** Synthetic generation only; RETRIEVE uses the production retriever, query store and HTTP embedding client. */
public final class QueryEmbeddingFaultWorkerMain {
    private QueryEmbeddingFaultWorkerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        String barrierUrl = required("P2_QUERY_BARRIER_URL");
        boolean pauseAfterQuery = Boolean.parseBoolean(required("P2_QUERY_PAUSE_AFTER_CONFIRM"));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(AgentWorkerApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(application -> registerPorts((GenericApplicationContext) application,
                barrierUrl, pauseAfterQuery))
                .run("--app.agent.workflow.enabled=true",
                "--app.agent.workflow.worker-enabled=true",
                "--app.agent.workflow.dispatcher-enabled=false",
                "--spring.main.banner-mode=off");
        Runtime.getRuntime().addShutdownHook(new Thread(context::close, "query-fault-worker-close"));
        System.out.println("QUERY_WORKER_READY");
        System.out.flush();
        try {
            new CountDownLatch(1).await();
        } finally {
            context.close();
        }
    }

    private static void registerPorts(GenericApplicationContext context, String barrierUrl,
            boolean pauseAfterQuery) {
        SnapshotPort snapshots = (accountId, requestId) -> {
            TutorLedger ledger = context.getBean(TutorLedger.class);
            String fileRef = ledger.getByRequestId(accountId, requestId).snapshotFileRef();
            return new Snapshot(fileRef, "合成题：用配方法求解。", List.of(), List.of(), false, null, null);
        };
        RolePipeline pipeline = (pipelineContext, steps) -> {
            String runId = pipelineContext.execution().runId();
            invokeSynthetic(steps, Role.SOLVE);
            invokeSynthetic(steps, Role.DIAGNOSE);
            String queryText = "用配方法求解二次方程";
            String queryDigest = sha256("RETRIEVE:" + queryText);
            steps.execute(new StepInvocation(Role.RETRIEVE, 1, queryDigest), () -> {
                EvidenceRetriever realRetriever = context.getBean(EvidenceRetriever.class);
                if (!(realRetriever instanceof PersonalEvidenceRetriever)
                        || !(context.getBean(RetrievalIndexStore.class) instanceof MybatisRetrievalIndexStore)) {
                    throw new IllegalStateException("production query retrieval beans required");
                }
                var retrieved = realRetriever.retrieve(new RetrievalQuery(
                        pipelineContext.execution().accountId(), runId,
                        pipelineContext.execution().questionId(), queryText, List.of("配方法"), 5));
                if (pauseAfterQuery) {
                    barrier(barrierUrl);
                }
                String output = "retrieved:" + retrieved.size();
                return new StepOutput(output, sha256(output));
            });
            invokeSynthetic(steps, Role.TEACH);
            invokeSynthetic(steps, Role.REVIEW);
            return new PipelineResult(true, "合成故障验证结果", List.of(), null);
        };
        context.registerBean("queryFaultSnapshotPort", SnapshotPort.class, () -> snapshots,
                definition -> definition.setPrimary(true));
        context.registerBean("queryFaultRolePipeline", RolePipeline.class, () -> pipeline,
                definition -> definition.setPrimary(true));
    }

    private static void invokeSynthetic(com.betterlearning.server.multiagent.StepExecutor steps, Role role) {
        String output = "synthetic:" + role.name();
        steps.execute(new StepInvocation(role, 1, sha256(role.name())),
                () -> new StepOutput(output, sha256(output)));
    }

    private static void barrier(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120)).GET().build();
        try {
            HttpResponse<Void> response = HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("query fault barrier failed");
            }
        } catch (IOException error) {
            throw new IllegalStateException("query fault barrier unavailable", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("query fault barrier interrupted", error);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
