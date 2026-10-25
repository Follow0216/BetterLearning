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
        TutoringInputBudget.validateModelImages(input.snapshot().images());
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
        List<TeachingEvidence> allowedEvidence = evidence.stream().map(node -> new TeachingEvidence(
                node.get("evidenceId").asString(), node.get("claim").asString(),
                node.path("sourceType").asString("HISTORY"),
                node.path("textOrigin").asString("UNVERIFIED_TEXT"),
                node.path("answerState").isString() ? node.get("answerState").asString() : null)).toList();
        JsonNode teach = session.call(Role.TEACH, context(solve, diagnosis, allowedEvidence));
        List<String> violations = inspect(input, solve, diagnosis, teach, allowedEvidence);
        JsonNode review = session.call(Role.REVIEW, reviewContext(solve, diagnosis, teach, allowedEvidence, violations));
        if (!review.get("approved").asBoolean() || !violations.isEmpty()) {
            Role target = target(review);
            Map<String, Object> repair = new LinkedHashMap<>();
            repair.put("reviewIssues", review.get("issues"));
            repair.put("programViolations", violations);
            // REVIEW已见历史；独立角色不得接收其自由文本code/detail，以免返工时泄漏历史。
            Map<String, Object> independentRepair = Map.of("reason", "INDEPENDENT_RECHECK_REQUIRED");
            if (target == Role.SOLVE) {
                // SOLVE返工仍不接收历史内容，也不复用可能被污染的最终答复。
                solve = session.call(Role.SOLVE, Map.of("repair", independentRepair));
                // 解法已改变时旧方法召回不再可靠；一次检索上限内安全降为无历史辅导。
                allowedEvidence = List.of();
            }
            if (target == Role.SOLVE || target == Role.DIAGNOSE) {
                diagnosis = session.call(Role.DIAGNOSE, Map.of("solve", solve, "repair", independentRepair));
            }
            if (target == Role.TEACH) {
                // 前稿仅供同一TEACH定向修订；不得把其中的历史或旧解带回独立角色。
                boundary.checkActive();
                if (!boundary.authorizeEvidence(Set.copyOf(retrievedIds))) {
                    throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
                }
                boundary.checkActive();
                repair.put("previousCandidate", teach.deepCopy());
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

    private static Map<String, Object> context(JsonNode solve, JsonNode diagnosis, Object evidence) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("solve", solve);
        data.put("diagnosis", diagnosis);
        data.put("allowedEvidence", evidence);
        data.put("independentRolesReceiveHistory", false);
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
            List<TeachingEvidence> evidence) {
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
            if (solve.get("sufficient").asBoolean() && ((answer.length() >= 3
                    && normalize(body).contains(answer)) || explicitConclusion(body, answer))) {
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
        for (TeachingEvidence item : evidence) {
            allowed.add(item.evidenceId());
            if (contradictoryHistoricalBinding(body, item.claim(), solve.get("answer").asString())) {
                violations.add("CONTRADICTORY_HISTORY_CONCLUSION");
            }
            if (item.claim().length() >= 24 && body.contains(item.claim())) {
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

    /** 已授权证据的模型投影；作答状态与原文分开，null不暗示已完成。 */
    private record TeachingEvidence(String evidenceId, String claim, String sourceType,
            String textOrigin, String answerState) {
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

    private static boolean explicitConclusion(String body, String answer) {
        String normalized = normalize(body);
        if (answer.isBlank()) {
            return false;
        }
        if (normalized.equals(answer)) {
            return true;
        }
        String token = Pattern.quote(answer) + "(?![A-Za-z0-9_.])";
        return Pattern.compile("(?:答案|结果|最终解|最终值)(?:是|为|等于|[:：])?" + token)
                .matcher(normalized).find();
    }

    private static boolean contradictoryHistoricalBinding(String body, String claim, String answer) {
        Pattern binding = Pattern.compile("^([a-zA-Z])=([+-]?[0-9]+(?:\\.[0-9]+)?)$");
        var old = binding.matcher(normalize(claim));
        var current = binding.matcher(normalize(answer));
        if (!old.matches() || !current.matches() || !old.group(1).equals(current.group(1))
                || new java.math.BigDecimal(old.group(2)).compareTo(new java.math.BigDecimal(current.group(2))) == 0) {
            return false;
        }
        String expression = normalize(claim);
        for (String sentence : body.split("[。！？!?;；\\n]")) {
            if (normalize(sentence).contains(expression)
                    && !Pattern.compile("错误|不成立|不对|不能|不应|不要|反例|并非|误写|有误")
                    .matcher(sentence).find()) {
                return true;
            }
        }
        return false;
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
            if (role == Role.TEACH || role == Role.REVIEW) {
                envelope.put("mode", input.mode());
            } else {
                envelope.put("visibility", "INTERNAL_ONLY_COMPLETE_CONCLUSIONS_REQUIRED");
            }
            envelope.put("questionMarkdown", input.snapshot().questionMarkdown());
            envelope.put("studentPrompt", input.prompt());
            envelope.put("strokeIds", input.strokeIds().stream().sorted().toList());
            envelope.put("writingMetadataKnown", input.writingMetadataKnown());
            envelope.put("strokeLocationsStatus", input.strokeLocationsStatus().name());
            envelope.put("strokeLocations", input.strokeLocations());
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
