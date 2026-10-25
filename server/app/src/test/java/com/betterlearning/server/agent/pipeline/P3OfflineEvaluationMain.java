package com.betterlearning.server.agent.pipeline;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.betterlearning.server.agent.config.AgentProperties;
import com.betterlearning.server.agent.model.AgentModelException;
import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.model.TutorModels.FrozenAnswer;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Call;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Input;
import com.betterlearning.server.agent.pipeline.RoleProtocol.PipelineFailure;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reply;
import com.betterlearning.server.agent.service.impl.LangChain4jTutoringModel;

import tools.jackson.databind.JsonNode;

/** 显式启动的真实模型离线评测入口，不由Surefire自动运行；不依赖数据库，不冒充生产账本。 */
public final class P3OfflineEvaluationMain {
    private P3OfflineEvaluationMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("dataset outputDirectory maxCases maxCalls commit required");
        }
        Path dataset = Path.of(args[0]).toRealPath();
        Path directory = Path.of(args[1]).toAbsolutePath();
        int maxCases = Integer.parseInt(args[2]);
        int maxCalls = Integer.parseInt(args[3]);
        if (maxCases < 1 || maxCases > 10 || maxCalls < 1 || maxCalls > 90 || Files.exists(directory)) {
            throw new IllegalArgumentException("bounded fresh evaluation directory required");
        }
        AgentProperties properties = properties();
        LangChain4jRoleModel model = new LangChain4jRoleModel(properties.getTutoring());
        model.validateConfiguration();
        Files.createDirectories(directory);
        List<String> lines = Files.readAllLines(dataset, StandardCharsets.UTF_8);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> trace = new ArrayList<>();
        int[] totalCalls = {0};
        boolean outcomeUnknown = false;
        for (String line : lines.subList(0, Math.min(maxCases, lines.size()))) {
            if (totalCalls[0] >= maxCalls || outcomeUnknown) {
                break;
            }
            JsonNode item = RoleOutputValidator.JSON.readTree(line);
            FrozenAnswer frozen = snapshot(dataset.getParent(), item);
            String prompt = item.path("studentIntent").asString();
            Set<String> strokes = item.path("snapshot").has("strokeIds")
                    ? Set.copyOf(RoleOutputValidator.strings(item.path("snapshot").get("strokeIds"))) : Set.of();
            Double confidence = item.path("snapshot").hasNonNull("ocrConfidence")
                    ? item.path("snapshot").get("ocrConfidence").asDouble() : null;
            Input input = new Input(prompt, item.path("mode").asString("HINT"), frozen, strokes, confidence);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", item.get("caseId").asString());
            row.put("familyId", item.get("familyId").asString());
            row.put("split", item.get("split").asString());
            row.put("caseDigest", TutoringPipeline.sha256(line));
            row.put("humanAssessment", Map.of("reviewers", List.of(), "status", "PENDING"));
            Map<String, Object> runs = new LinkedHashMap<>();
            row.put("runs", runs);
            rows.add(row);
            Map<String, Object> baseline = new LinkedHashMap<>();
            runs.put("baseline", baseline);
            baseline.put("status", "RUNNING");
            baseline.put("usage", unknownUsage(frozen.images().size()));
            baseline.put("providerCallState", "CALL_INTENT");
            baseline.put("attemptId", UUID.randomUUID().toString());
            totalCalls[0]++;
            persist(directory, rows, trace);
            long started = System.nanoTime();
            try {
                baseline.put("answerMarkdown", new LangChain4jTutoringModel(properties).answer(prompt, frozen));
                baseline.put("status", "SUCCEEDED");
                baseline.put("providerCallState", "CONFIRMED");
            } catch (AgentModelException exception) {
                baseline.put("status", "OUTCOME_UNKNOWN");
                baseline.put("providerCallState", "UNKNOWN");
                baseline.put("reason", exception.failure().name());
                outcomeUnknown = true;
            }
            baseline.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
            Map<String, Object> pipeline = new LinkedHashMap<>();
            runs.put("pipeline", pipeline);
            pipeline.put("status", "NOT_RUN");
            pipeline.put("citations", List.of());
            List<Map<String, Object>> steps = new ArrayList<>();
            pipeline.put("steps", steps);
            pipeline.put("unknownChargeCount", 0);
            pipeline.put("generationCalls", 0);
            if (outcomeUnknown) {
                persist(directory, rows, trace);
                break;
            }
            List<JsonNode> evidence = item.get("history") == null ? List.of()
                    : item.get("history").valueStream().map(JsonNode::deepCopy).toList();
            GenerationBoundary boundary = new GenerationBoundary() {
                @Override
                public Reply generate(Call call) {
                    if (totalCalls[0] >= maxCalls) {
                        throw new PipelineFailure(Reason.BUDGET_EXCEEDED);
                    }
                    totalCalls[0]++;
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("role", call.role().name());
                    step.put("sequence", call.sequence());
                    step.put("attemptId", UUID.randomUUID().toString());
                    step.put("providerCallState", "CALL_INTENT");
                    step.put("inputSha256", call.inputDigest());
                    step.put("usage", unknownUsage(frozen.images().size()));
                    steps.add(step);
                    trace.add(step);
                    pipeline.put("generationCalls", steps.size());
                    persist(directory, rows, trace);
                    long callStarted = System.nanoTime();
                    try {
                        Reply reply = model.generate(call);
                        step.put("providerCallState", "CONFIRMED");
                        step.put("model", reply.model());
                        step.put("usage", reply.usage());
                        step.put("outputSha256", TutoringPipeline.sha256(reply.json()));
                        // 保存通过schema的简明结论，原始未知字段/隐藏思考绝不落盘。
                        step.put("structuredConclusion", RoleOutputValidator.parse(call.role(), reply.json(), input));
                        return reply;
                    } catch (PipelineFailure exception) {
                        if (exception.reason() == Reason.OUTCOME_UNKNOWN) {
                            step.put("providerCallState", "UNKNOWN");
                            pipeline.put("unknownChargeCount", 1);
                        } else if (exception.reason() == Reason.INVALID_OUTPUT) {
                            step.put("providerCallState", "CONFIRMED");
                        } else {
                            step.put("providerCallState", "NOT_SENT");
                        }
                        step.put("reason", exception.reason().name());
                        throw exception;
                    } finally {
                        step.put("latencyMs", (System.nanoTime() - callStarted) / 1_000_000);
                        persist(directory, rows, trace);
                    }
                }

                @Override
                public void checkActive() {
                    // 离线工具没有公开发布/取消API；生产调用必须使用P2持久执行边界。
                }

                @Override
                public List<JsonNode> retrieve(List<String> tags) {
                    // 受控离线fixture，不能计为P4真实检索表现；不会向模型传入goldCandidate。
                    return evidence;
                }

                @Override
                public boolean authorizeEvidence(Set<String> ids) {
                    Set<String> allowed = evidence.stream().map(node -> node.get("evidenceId").asString())
                            .collect(java.util.stream.Collectors.toSet());
                    return allowed.containsAll(ids);
                }
            };
            started = System.nanoTime();
            try {
                var candidate = new TutoringPipeline().run(input, boundary);
                pipeline.put("status", "SUCCEEDED");
                pipeline.put("answerMarkdown", candidate.bodyMarkdown());
                pipeline.put("selectedEvidenceIds", candidate.evidenceIds());
                pipeline.put("audit", candidate.audit());
            } catch (PipelineFailure exception) {
                pipeline.put("status", exception.reason() == Reason.OUTCOME_UNKNOWN ? "OUTCOME_UNKNOWN" : "WITHHELD");
                pipeline.put("reason", exception.reason().name());
                outcomeUnknown = exception.reason() == Reason.OUTCOME_UNKNOWN;
            }
            pipeline.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
            persist(directory, rows, trace);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("runId", UUID.randomUUID().toString());
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("commit", args[4]);
        manifest.put("evidenceClass", "real_model_offline");
        manifest.put("realModelEvidence", Map.of("redactedTraceDigest", hash(Files.readAllBytes(directory.resolve("trace.json")))));
        manifest.put("dataset", Map.of("path", dataset.toString(), "sha256", hash(Files.readAllBytes(dataset)), "caseCount", lines.size()));
        manifest.put("split", "all");
        String modelName = properties.getTutoring().getModel();
        manifest.put("pipeline", Map.of("provider", "OpenAI-compatible", "model", modelName,
                "modelVersion", "provider alias; immutable revision unavailable", "promptVersion", RoleProtocol.PROMPT_VERSION,
                "indexVersion", "none; controlled fixture evidence"));
        manifest.put("baseline", Map.of("provider", "OpenAI-compatible", "model", modelName,
                "modelVersion", "provider alias; immutable revision unavailable", "promptVersion", "existing-v1"));
        manifest.put("seed", null);
        manifest.put("budget", Map.of("maxGenerationCallsPerRun", 8, "maxReworkLoops", 1,
                "maxTotalCalls", maxCalls, "maxCases", maxCases, "actualTotalCalls", totalCalls[0]));
        manifest.put("resultsPath", "run-results.jsonl");
        manifest.put("notes", "Real provider calls on synthetic candidate data; no human scores; P3 quality gate NOT_PASSED.");
        Files.writeString(directory.resolve("run-manifest.json"), RoleOutputValidator.JSON.writeValueAsString(manifest));
        System.out.println("Evaluated cases=" + rows.size() + "; calls=" + totalCalls[0] + "; qualityGate=NOT_PASSED");
    }

    private static FrozenAnswer snapshot(Path base, JsonNode item) throws Exception {
        List<ModelImage> images = new ArrayList<>();
        for (JsonNode image : item.get("snapshot").get("images")) {
            Path path = base.resolve(image.get("path").asString()).normalize().toRealPath();
            if (!path.startsWith(base.toRealPath())) {
                throw new IllegalArgumentException("image outside dataset root");
            }
            byte[] bytes = Files.readAllBytes(path);
            if (!hash(bytes).equals(image.get("sha256").asString())) {
                throw new IllegalArgumentException("image digest mismatch");
            }
            String position = "worldBounds=" + image.get("worldBounds") + "; pixelWidth=" + image.get("pixelWidth")
                    + "; pixelHeight=" + image.get("pixelHeight");
            images.add(new ModelImage("image/png", bytes, position));
        }
        return new FrozenAnswer(item.get("caseId").asString(), item.get("questionMarkdown").asString(), images);
    }

    private static AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        var provider = properties.getTutoring();
        provider.setBaseUrl(System.getenv("APP_AGENT_TUTORING_BASE_URL"));
        provider.setModel(System.getenv("APP_AGENT_TUTORING_MODEL"));
        provider.setApiKey(System.getenv("APP_AGENT_TUTORING_API_KEY"));
        provider.setTimeoutSeconds(120);
        return properties;
    }

    private static Map<String, Object> unknownUsage(int images) {
        return Map.of("images", images, "costKnown", false, "ocrCalls", 0, "embeddingCalls", 0, "rerankCalls", 0);
    }

    private static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static void persist(Path directory, List<Map<String, Object>> rows, List<Map<String, Object>> trace) {
        try {
            StringBuilder result = new StringBuilder();
            for (Map<String, Object> row : rows) {
                result.append(RoleOutputValidator.JSON.writeValueAsString(row)).append('\n');
            }
            Files.writeString(directory.resolve("run-results.jsonl"), result);
            Files.writeString(directory.resolve("trace.json"), RoleOutputValidator.JSON.writeValueAsString(trace));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot persist evaluation audit; stop before further calls");
        }
    }
}
