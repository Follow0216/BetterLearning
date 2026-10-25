package com.betterlearning.server.agent.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.multiagent.CitationMarkers;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Audit;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Call;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Candidate;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Input;
import com.betterlearning.server.agent.pipeline.RoleProtocol.PipelineFailure;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reply;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Role;

import tools.jackson.databind.JsonNode;

/** 有限四角色执行器；只产生待发布候选，持久状态/重放/取消CAS由P2边界拥有。 */
public final class TutoringPipeline {
    private static final Pattern LINK = Pattern.compile("(?i)(https?://|data:|javascript:|<[^>]+>|!?\\[[^]]*]\\s*\\(|\\[[0-9]+])");

    public Candidate run(Input input, GenerationBoundary boundary) {
        validateImages(input);
        Session session = new Session(input, boundary);
        JsonNode solve = session.call(Role.SOLVE, Map.of());
        JsonNode diagnosis = session.call(Role.DIAGNOSE, Map.of("solve", solve));
        boundary.checkActive();
        List<JsonNode> evidence = boundary.retrieve(RoleOutputValidator.strings(solve.get("methodTags")));
        boundary.checkActive();
        RoleOutputValidator.evidence(evidence);
        Set<String> retrievedIds = new LinkedHashSet<>();
        evidence.forEach(node -> retrievedIds.add(node.get("evidenceId").asString()));
        if (!boundary.authorizeEvidence(Set.copyOf(retrievedIds))) {
            throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
        }
        boundary.checkActive();
        // 只投影模型需要的字段；不把URL、账号或任意工具元数据放进提示词。
        List<Map<String, String>> allowedEvidence = evidence.stream().map(node -> Map.of(
                "evidenceId", node.get("evidenceId").asString(),
                "claim", node.get("claim").asString(),
                "sourceType", node.path("sourceType").asString("HISTORY"))).toList();
        JsonNode teach = session.call(Role.TEACH, context(solve, diagnosis, allowedEvidence));
        List<String> violations = inspect(input, solve, diagnosis, teach, allowedEvidence);
        JsonNode review = session.call(Role.REVIEW, reviewContext(solve, diagnosis, teach, allowedEvidence, violations));
        if (!review.get("approved").asBoolean() || !violations.isEmpty()) {
            Role target = target(review);
            Map<String, Object> repair = new LinkedHashMap<>();
            repair.put("reviewIssues", review.get("issues"));
            repair.put("programViolations", violations);
            if (target == Role.SOLVE) {
                // SOLVE返工仍不接收历史内容，也不复用可能被污染的最终答复。
                solve = session.call(Role.SOLVE, Map.of("repair", repair));
                // 解法已改变时旧方法召回不再可靠；一次检索上限内安全降为无历史辅导。
                allowedEvidence = List.of();
            }
            if (target == Role.SOLVE || target == Role.DIAGNOSE) {
                diagnosis = session.call(Role.DIAGNOSE, Map.of("solve", solve, "repair", repair));
            }
            Map<String, Object> teaching = context(solve, diagnosis, allowedEvidence);
            teaching.put("repair", repair);
            teach = session.call(Role.TEACH, teaching);
            violations = inspect(input, solve, diagnosis, teach, allowedEvidence);
            review = session.call(Role.REVIEW, reviewContext(solve, diagnosis, teach, allowedEvidence, violations));
        }
        if (!review.get("approved").asBoolean() || !violations.isEmpty()) {
            throw new PipelineFailure(Reason.REVIEW_REJECTED);
        }
        List<String> selected = RoleOutputValidator.strings(teach.get("evidenceIds"));
        boundary.checkActive();
        if (!boundary.authorizeEvidence(Set.copyOf(selected))) {
            throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
        }
        boundary.checkActive();
        return new Candidate(input.mode(), body(teach), selected, solve, diagnosis, review, session.audit);
    }

    private static void validateImages(Input input) {
        if (input.snapshot().images().size() > 256) {
            throw new PipelineFailure(Reason.INVALID_INPUT);
        }
        long bytes = 0;
        for (ModelImage image : input.snapshot().images()) {
            if (image.byteCount() == 0 || (!"image/png".equals(image.mediaType())
                    && !"image/jpeg".equals(image.mediaType()))) {
                throw new PipelineFailure(Reason.INVALID_INPUT);
            }
            bytes += image.byteCount();
        }
        if (bytes > 32L * 1024 * 1024) {
            throw new PipelineFailure(Reason.INVALID_INPUT);
        }
    }

    private static Map<String, Object> context(JsonNode solve, JsonNode diagnosis, Object evidence) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("solve", solve);
        data.put("diagnosis", diagnosis);
        data.put("allowedEvidence", evidence);
        return data;
    }

    private static Map<String, Object> reviewContext(JsonNode solve, JsonNode diagnosis, JsonNode teach,
            Object evidence, List<String> violations) {
        Map<String, Object> data = context(solve, diagnosis, evidence);
        data.put("candidate", teach);
        data.put("programViolations", violations);
        return data;
    }

    private static List<String> inspect(Input input, JsonNode solve, JsonNode diagnosis, JsonNode teach,
            List<Map<String, String>> evidence) {
        List<String> violations = new ArrayList<>();
        String body = body(teach);
        if (body.isBlank()) {
            violations.add("EMPTY_TEACHING");
        }
        if ("HINT".equals(input.mode())) {
            if (!teach.get("fullSolution").isNull()) {
                violations.add("HINT_FULL_SOLUTION");
            }
            String answer = normalize(solve.get("answer").asString());
            if (solve.get("sufficient").asBoolean() && answer.length() >= 3
                    && normalize(body).contains(answer)) {
                violations.add("HINT_EXACT_ANSWER");
            }
        }
        if ((!solve.get("sufficient").asBoolean() || "UNCERTAIN".equals(diagnosis.get("status").asString()))
                && teach.get("clarification").asString().isBlank()) {
            violations.add("MISSING_CLARIFICATION");
        }
        if (LINK.matcher(body).find()) {
            violations.add("UNTRUSTED_LINK_OR_CITATION_MARKER");
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (Map<String, String> item : evidence) {
            allowed.add(item.get("evidenceId"));
            if (item.get("claim").length() >= 24 && body.contains(item.get("claim"))) {
                violations.add("HISTORY_VERBATIM_COPY");
            }
        }
        Map<String, String> markerIds = new LinkedHashMap<>();
        RoleOutputValidator.strings(teach.get("evidenceIds")).forEach(id -> markerIds.put(id, id));
        try {
            CitationMarkers.resolve(body, markerIds);
        } catch (IllegalArgumentException exception) {
            violations.add("EVIDENCE_PLACEMENT_MISMATCH");
        }
        if (!allowed.containsAll(RoleOutputValidator.strings(teach.get("evidenceIds")))) {
            violations.add("UNKNOWN_EVIDENCE_ID");
        }
        return List.copyOf(violations);
    }

    private static Role target(JsonNode review) {
        Role target = Role.TEACH;
        for (JsonNode issue : review.get("issues")) {
            Role proposed = Role.valueOf(issue.get("target").asString());
            if (proposed.ordinal() < target.ordinal()) {
                target = proposed;
            }
        }
        return target;
    }

    private static String body(JsonNode teach) {
        List<String> sections = new ArrayList<>();
        for (String name : List.of("hint", "nextStep", "clarification", "fullSolution")) {
            JsonNode section = teach.get(name);
            if (section.isString() && !section.asString().isBlank()) {
                sections.add(section.asString());
            }
        }
        return String.join("\n\n", sections);
    }

    private static String normalize(String value) {
        return value.replaceAll("[\\s${}\\\\]", "");
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static final class Session {
        private final Input input;
        private final GenerationBoundary boundary;
        private final List<Audit> audit = new ArrayList<>();
        private int sequence;

        private Session(Input input, GenerationBoundary boundary) {
            this.input = input;
            this.boundary = boundary;
        }

        private JsonNode call(Role role, Map<String, Object> context) {
            boundary.checkActive();
            if (sequence >= RoleProtocol.MAX_GENERATION_CALLS) {
                throw new PipelineFailure(Reason.BUDGET_EXCEEDED);
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("mode", input.mode());
            envelope.put("questionMarkdown", input.snapshot().questionMarkdown());
            envelope.put("studentPrompt", input.prompt());
            envelope.put("strokeIds", input.strokeIds().stream().sorted().toList());
            envelope.put("writingMetadataKnown", input.writingMetadataKnown());
            envelope.put("ocrConfidence", input.ocrConfidence());
            envelope.put("ocrText", input.ocrText());
            envelope.put("context", context);
            String json = RoleOutputValidator.JSON.writeValueAsString(envelope);
            Call call = new Call(++sequence, role, RolePrompts.forRole(role), json, input.snapshot());
            long started = System.nanoTime();
            Reply reply = boundary.generate(call);
            boundary.checkActive();
            JsonNode output = RoleOutputValidator.parse(role, reply.json(), input);
            audit.add(new Audit(sequence, role, RoleProtocol.PROMPT_VERSION, reply.model(), reply.usage(),
                    (System.nanoTime() - started) / 1_000_000, call.inputDigest(), sha256(reply.json())));
            return output;
        }
    }
}
