package com.betterlearning.server.agent.pipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.betterlearning.server.agent.pipeline.RoleProtocol.Input;
import com.betterlearning.server.agent.pipeline.RoleProtocol.PipelineFailure;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Role;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocationsStatus;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 拒绝多余字段、宽松类型转换、伪造引用和不受支持的观察；语义质量仍须独立模型/人工评测。 */
public final class RoleOutputValidator {
    public static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private RoleOutputValidator() {
    }

    public static JsonNode parse(Role role, String value, Input input) {
        if (value == null || value.length() > 32768) {
            throw new PipelineFailure(Reason.INVALID_OUTPUT);
        }
        try {
            JsonNode node = JSON.readTree(value);
            boolean uncertainOcr = input.ocrConfidence() != null && input.ocrConfidence() < 0.8
                    && !input.strokeIds().isEmpty();
            // 只有完整位置映射才能支持具体笔划观察；旧快照和超预算快照不猜测位置。
            boolean unlocatedWriting = !input.writingMetadataKnown() || (!input.strokeIds().isEmpty()
                    && input.strokeLocationsStatus() != StrokeLocationsStatus.COMPLETE);
            switch (role) {
                case SOLVE -> solve(node);
                case DIAGNOSE -> diagnose(node, input, unlocatedWriting || uncertainOcr);
                case TEACH -> teach(node);
                case REVIEW -> review(node);
                default -> throw new PipelineFailure(Reason.INVALID_OUTPUT);
            }
            boolean locationBudgetExceeded = TutoringInputBudget.locationBudgetExceeded(input);
            if (locationBudgetExceeded && role == Role.DIAGNOSE) {
                return JSON.readTree("""
                        {"status":"UNCERTAIN","summary":"完整笔划定位数据超过当前单次处理范围，本轮无法逐笔判断对错。",
                        "observations":[],"clarification":"本次作答的定位数据超过当前处理范围。请保留原作答；重试同一份内容或仅提升图片清晰度不能解除容量限制。若需继续精确检查，请将目标步骤另行写成较小范围的独立作答后重新发起辅导。"}
                        """);
            }
            if (locationBudgetExceeded && role == Role.TEACH) {
                var clarification = JSON.createObjectNode().put("hint", "").put("nextStep", "")
                        .put("clarification", TutoringInputBudget.LOCATION_LIMIT_CLARIFICATION)
                        .putNull("fullSolution");
                clarification.putArray("evidenceIds");
                return clarification;
            }
            if (unlocatedWriting && !uncertainOcr && role == Role.DIAGNOSE) {
                return JSON.readTree("""
                        {"status":"UNCERTAIN","summary":"当前缺少笔划到图像位置的可核对映射，不能可靠定位具体步骤。",
                        "observations":[],"clarification":"目前还不能可靠对应你写的具体步骤，请说明希望检查的那一步或补充清晰作答图片。"}
                        """);
            }
            if (unlocatedWriting && !uncertainOcr && role == Role.TEACH) {
                return JSON.readTree("""
                        {"hint":"","nextStep":"","clarification":"目前还不能可靠对应你写的具体步骤，请说明希望检查的那一步或补充清晰作答图片。",
                        "fullSolution":null,"evidenceIds":[]}
                        """);
            }
            if (uncertainOcr && role == Role.DIAGNOSE) {
                return JSON.readTree("""
                        {"status":"UNCERTAIN","summary":"当前笔迹识别不可靠，尚不能判断是否有误。",
                        "observations":[],"clarification":"请确认不清晰的符号或补充清晰作答图，再检查具体步骤。"}
                        """);
            }
            if (uncertainOcr && role == Role.TEACH) {
                return JSON.readTree("""
                        {"hint":"","nextStep":"","clarification":"当前笔迹识别不够可靠，请确认不清晰的符号或补充清晰作答图，再检查具体步骤。",
                        "fullSolution":null,"evidenceIds":[]}
                        """);
            }
            return node;
        } catch (RuntimeException exception) {
            throw new PipelineFailure(Reason.INVALID_OUTPUT);
        }
    }

    public static List<String> strings(JsonNode node) {
        return node.valueStream().map(JsonNode::asString).toList();
    }

    public static void evidence(List<JsonNode> evidence) {
        if (evidence == null || evidence.size() > 5) {
            throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
        }
        Set<String> ids = new HashSet<>();
        int total = 0;
        for (JsonNode node : evidence) {
            if (node == null || !node.isObject() || !node.has("evidenceId") || !node.has("claim")) {
                throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
            }
            String id = node.path("evidenceId").asString("");
            String claim = node.path("claim").asString("");
            try {
                if (!UUID.fromString(id).toString().equals(id) || !ids.add(id) || claim.isBlank()
                        || claim.length() > 4000) {
                    throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
                }
            } catch (IllegalArgumentException exception) {
                throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
            }
            total += claim.length();
        }
        if (total > 12000) {
            throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
        }
    }

    private static void solve(JsonNode node) {
        fields(node, "summary", "answer", "methodTags", "checkpoints", "sufficient", "answerComplete");
        text(node, "summary", 1200, false);
        text(node, "answer", 800, false);
        textArray(node.get("methodTags"), 8, 80);
        textArray(node.get("checkpoints"), 12, 400);
        boolean sufficient = bool(node, "sufficient");
        require(bool(node, "answerComplete") == sufficient);
        if (sufficient) {
            require(!java.util.regex.Pattern.compile("提示|下一步|试着|你可以|请先|思考")
                    .matcher(node.get("answer").asString()).find());
        }
    }

    private static void diagnose(JsonNode node, Input input, boolean clarificationOnly) {
        fields(node, "status", "summary", "observations", "clarification");
        text(node, "summary", 1200, false);
        text(node, "clarification", 1600, true);
        String status = text(node, "status", 32, false);
        require(Set.of("NO_WRITING", "OBSERVED", "UNCERTAIN").contains(status));
        JsonNode observations = node.get("observations");
        array(observations, 12);
        // 先校验全部字段结构；缺映射或低置信OCR时不采纳模型诊断，交由parse统一安全澄清。
        for (JsonNode observation : observations) {
            fields(observation, "kind", "description", "strokeIds");
            require(Set.of("CORRECT", "ERROR", "UNCERTAIN").contains(text(observation, "kind", 32, false)));
            text(observation, "description", 1000, false);
            textArray(observation.get("strokeIds"), 128, 128);
            List<String> ids = strings(observation.get("strokeIds"));
            require(!ids.isEmpty() && new HashSet<>(ids).size() == ids.size());
        }
        if (clarificationOnly) {
            return;
        }
        if (input.strokeIds().isEmpty()) {
            require("NO_WRITING".equals(status) && observations.isEmpty());
        } else {
            require(!"NO_WRITING".equals(status));
        }
        if ("UNCERTAIN".equals(status)) {
            require(!node.get("clarification").asString().isBlank());
        }
        for (JsonNode observation : observations) {
            require(input.strokeIds().containsAll(strings(observation.get("strokeIds"))));
        }
    }

    private static void teach(JsonNode node) {
        fields(node, "hint", "nextStep", "clarification", "fullSolution", "evidenceIds");
        text(node, "hint", 1600, true);
        text(node, "nextStep", 1600, true);
        text(node, "clarification", 1600, true);
        if (!node.get("fullSolution").isNull()) {
            text(node, "fullSolution", 12000, false);
        }
        textArray(node.get("evidenceIds"), 3, 36);
        List<String> ids = strings(node.get("evidenceIds"));
        require(new HashSet<>(ids).size() == ids.size());
    }

    private static void review(JsonNode node) {
        fields(node, "approved", "solutionComplete", "mathematicsOk", "diagnosisGrounded", "hintSafe",
                "citationsSupported", "historyNotAuthority", "issues");
        boolean approved = bool(node, "approved");
        boolean all = bool(node, "solutionComplete") & bool(node, "mathematicsOk") & bool(node, "diagnosisGrounded")
                & bool(node, "hintSafe") & bool(node, "citationsSupported") & bool(node, "historyNotAuthority");
        JsonNode issues = node.get("issues");
        array(issues, 8);
        require(approved == (all && issues.isEmpty()));
        require(approved || !issues.isEmpty());
        for (JsonNode issue : issues) {
            fields(issue, "target", "code", "detail");
            require(Set.of("SOLVE", "DIAGNOSE", "TEACH").contains(text(issue, "target", 16, false)));
            text(issue, "code", 80, false);
            text(issue, "detail", 600, false);
        }
    }

    private static void fields(JsonNode node, String... names) {
        require(node != null && node.isObject());
        require(node.propertyNames().equals(Set.of(names)));
    }

    private static void textArray(JsonNode node, int max, int maxText) {
        array(node, max);
        for (JsonNode item : node) {
            require(item.isString() && !item.asString().isBlank() && item.asString().length() <= maxText);
        }
    }

    private static void array(JsonNode node, int max) {
        require(node != null && node.isArray() && node.size() <= max);
    }

    private static String text(JsonNode node, String field, int max, boolean emptyAllowed) {
        JsonNode value = node.get(field);
        require(value != null && value.isString());
        String text = value.asString();
        require(text.length() <= max && (emptyAllowed || !text.isBlank()));
        return text;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isBoolean());
        return value.asBoolean();
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new PipelineFailure(Reason.INVALID_OUTPUT);
        }
    }
}
