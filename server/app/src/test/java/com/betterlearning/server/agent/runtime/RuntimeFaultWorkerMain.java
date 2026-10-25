package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.MultiAgentModels.PipelineResult;
import com.betterlearning.server.multiagent.MultiAgentModels.Role;
import com.betterlearning.server.multiagent.MultiAgentModels.Snapshot;
import com.betterlearning.server.multiagent.MultiAgentModels.Status;
import com.betterlearning.server.multiagent.MultiAgentModels.StepInvocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StepOutput;
import com.betterlearning.server.multiagent.RolePipeline;
import com.betterlearning.server.multiagent.SnapshotPort;
import com.betterlearning.server.multiagent.TutorLedger;
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

/** 子 JVM 故障注入入口；不注册生产配置类，也不读取真实模型凭据。 */
public final class RuntimeFaultWorkerMain {
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);
    private static final List<Role> ROLES = List.of(Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW);

    private RuntimeFaultWorkerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        String providerUrl = requiredEnvironment("P2_PROVIDER_URL");
        FaultMode mode = FaultMode.valueOf(requiredEnvironment("P2_FAULT_MODE"));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(AgentWorkerApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(application -> registerTestPorts((GenericApplicationContext) application,
                providerUrl, mode, http))
                .run("--app.agent.workflow.enabled=true",
                "--app.agent.workflow.worker-enabled=true",
                "--app.agent.workflow.dispatcher-enabled=false",
                "--spring.main.banner-mode=off");
        Runtime.getRuntime().addShutdownHook(new Thread(context::close, "p2-worker-context-close"));
        System.out.println("P2_WORKER_READY");
        System.out.flush();
        try {
            new CountDownLatch(1).await();
        } finally {
            context.close();
        }
    }

    private static void registerTestPorts(GenericApplicationContext context, String providerUrl,
            FaultMode mode, HttpClient http) {
        SnapshotPort snapshots = (accountId, requestId) -> {
            TutorLedger ledger = context.getBean(TutorLedger.class);
            String fileRef = ledger.getByRequestId(accountId, requestId).snapshotFileRef();
            return new Snapshot(fileRef, "题目：请给出第一步提示", List.of(), List.of(), false, null, null);
        };
        RolePipeline pipeline = (execution, steps) -> {
            String runId = execution.execution().runId();
            for (Role role : ROLES) {
                StepInvocation invocation = new StepInvocation(role, 1, digest(role.name()));
                steps.execute(invocation, () -> {
                    String output = get(http, providerUrl, "/call?runId=" + runId + "&role=" + role.name());
                    if (mode == FaultMode.AFTER_RESPONSE && role == Role.SOLVE) {
                        barrier(http, providerUrl, runId, "response");
                    }
                    return new StepOutput(output, digest(output));
                });
                if (mode == FaultMode.AFTER_CONFIRM && role == Role.SOLVE) {
                    barrier(http, providerUrl, runId, "confirmed");
                }
            }
            return new PipelineResult(true, "提示：先整理题目中的已知条件。", List.of(), null);
        };
        context.registerBean("p2SnapshotPort", SnapshotPort.class, () -> snapshots,
                definition -> definition.setPrimary(true));
        context.registerBean("p2RolePipeline", RolePipeline.class, () -> pipeline,
                definition -> definition.setPrimary(true));
        if (mode == FaultMode.AFTER_PUBLISH) {
            RunStepActivities afterPublish = (accountId, runId, stepId, modelTimeoutMillis) -> {
                RunStepActivities delegate = context.getBean("runStepActivities", RunStepActivities.class);
                delegate.executeStep(accountId, runId, stepId, modelTimeoutMillis);
                TutorLedger ledger = context.getBean(TutorLedger.class);
                if (ledger.execution(accountId, runId).status() == Status.SUCCEEDED) {
                    barrier(http, providerUrl, runId, "published");
                }
            };
            context.registerBean("p2AfterPublishActivity", RunStepActivities.class, () -> afterPublish,
                    definition -> definition.setPrimary(true));
        }
    }

    private static void barrier(HttpClient http, String base, String runId, String point) {
        get(http, base, "/barrier?runId=" + runId + "&point=" + point);
    }

    private static String get(HttpClient http, String base, String path) {
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        HttpRequest request = HttpRequest.newBuilder(URI.create(normalized + path))
                .timeout(HTTP_TIMEOUT).GET().build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("测试 provider HTTP 状态异常");
            }
            return response.body();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试 provider 调用被中断", error);
        } catch (IOException error) {
            throw new IllegalStateException("测试 provider 调用失败", error);
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 必填");
        }
        return value;
    }

    private enum FaultMode { NONE, AFTER_CONFIRM, AFTER_RESPONSE, AFTER_PUBLISH }
}
