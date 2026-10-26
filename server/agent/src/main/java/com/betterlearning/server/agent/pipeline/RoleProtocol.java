package com.betterlearning.server.agent.pipeline;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

import com.betterlearning.server.agent.model.TutorModels.FrozenAnswer;
import com.betterlearning.server.multiagent.MultiAgentModels.AnswerInputKind;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocation;
import com.betterlearning.server.multiagent.MultiAgentModels.ConversationContext;
import com.betterlearning.server.multiagent.ConversationContexts;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocationsStatus;

import tools.jackson.databind.JsonNode;

/** Agent内部角色协议；不是HTTP/common DTO，不含供应商密钥或隐藏思维链。 */
public final class RoleProtocol {
    public static final String PROMPT_VERSION = "p3-roles-v9-answer-input";
    public static final int MAX_GENERATION_CALLS = 8;
    public static final int MAX_STROKE_LOCATIONS = 2000;
    public static final int MAX_STROKE_LOCATIONS_JSON_BYTES = 256 * 1024;

    private RoleProtocol() {
    }

    public enum Role {
        SOLVE, DIAGNOSE, TEACH, REVIEW
    }

    public enum Reason {
        INVALID_INPUT, INVALID_OUTPUT, MODEL_NOT_CONFIGURED, MODEL_INPUT_TOO_LARGE, OUTCOME_UNKNOWN,
        BUDGET_EXCEEDED, REVIEW_REJECTED, EVIDENCE_REJECTED, CANCELLED
    }

    /** 题面和作答类型由服务器冻结；SNAPSHOT须完整授权校验，NONE只提供真实题面与文字。 */
    public record Input(String prompt, String mode, FrozenAnswer snapshot, Set<String> strokeIds,
            Double ocrConfidence, boolean writingMetadataKnown, String ocrText,
            StrokeLocationsStatus strokeLocationsStatus, List<StrokeLocation> strokeLocations,
            ConversationContext conversationContext, AnswerInputKind answerInputKind) {
        public Input(String prompt, String mode, FrozenAnswer snapshot, Set<String> strokeIds,
                Double ocrConfidence, boolean writingMetadataKnown, String ocrText,
                StrokeLocationsStatus strokeLocationsStatus, List<StrokeLocation> strokeLocations,
                ConversationContext conversationContext) {
            this(prompt, mode, snapshot, strokeIds, ocrConfidence, writingMetadataKnown, ocrText,
                    strokeLocationsStatus, strokeLocations, conversationContext, AnswerInputKind.SNAPSHOT);
        }

        public Input(String prompt, String mode, FrozenAnswer snapshot, Set<String> strokeIds,
                Double ocrConfidence, boolean writingMetadataKnown, String ocrText,
                StrokeLocationsStatus strokeLocationsStatus, List<StrokeLocation> strokeLocations) {
            this(prompt, mode, snapshot, strokeIds, ocrConfidence, writingMetadataKnown, ocrText,
                    strokeLocationsStatus, strokeLocations, ConversationContexts.empty());
        }

        public Input(String prompt, String mode, FrozenAnswer snapshot, Set<String> strokeIds,
                Double ocrConfidence, boolean writingMetadataKnown, String ocrText) {
            this(prompt, mode, snapshot, strokeIds, ocrConfidence, writingMetadataKnown, ocrText,
                    StrokeLocationsStatus.UNAVAILABLE, List.of());
        }

        public Input(String prompt, String mode, FrozenAnswer snapshot, Set<String> strokeIds, Double ocrConfidence) {
            this(prompt, mode, snapshot, strokeIds, ocrConfidence, strokeIds != null, null);
        }

        public Input {
            try {
                ConversationContexts.validate(conversationContext);
            } catch (ConversationContexts.ContextTooLargeException exception) {
                throw new PipelineFailure(Reason.MODEL_INPUT_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                throw new PipelineFailure(Reason.INVALID_INPUT);
            }
            mode = mode == null ? "HINT" : mode;
            strokeIds = strokeIds == null ? Set.of() : Set.copyOf(strokeIds);
            if (prompt == null || prompt.isBlank() || prompt.length() > 4000
                    || (!"HINT".equals(mode) && !"FULL_SOLUTION".equals(mode))
                    || answerInputKind == null || snapshot == null
                    || snapshot.questionMarkdown() == null || snapshot.questionMarkdown().isBlank()
                    || (answerInputKind == AnswerInputKind.SNAPSHOT && (snapshot.fileRef() == null
                    || snapshot.fileRef().isBlank() || snapshot.images().isEmpty()))
                    || (answerInputKind == AnswerInputKind.NONE && (snapshot.fileRef() != null
                    || !snapshot.images().isEmpty() || !strokeIds.isEmpty() || writingMetadataKnown
                    || ocrText != null || ocrConfidence != null || strokeLocationsStatus != StrokeLocationsStatus.UNAVAILABLE
                    || strokeLocations == null || !strokeLocations.isEmpty()))
                    || (ocrText != null && ocrText.length() > 16000) || (ocrConfidence != null
                    && (!Double.isFinite(ocrConfidence) || ocrConfidence < 0 || ocrConfidence > 1))) {
                throw new PipelineFailure(Reason.INVALID_INPUT);
            }
            strokeLocations = validateLocations(strokeLocationsStatus, strokeLocations, strokeIds,
                    writingMetadataKnown, snapshot.images().size());
        }

        @Override
        public String toString() {
            return "RoleInput[mode=" + mode + ", images=" + snapshot.images().size() + "]";
        }
    }

    /** 公共记录保证有限正边界；此处再次验证完整性和实际送入模型的位置列表字节预算。 */
    private static List<StrokeLocation> validateLocations(StrokeLocationsStatus status,
            List<StrokeLocation> locations, Set<String> strokeIds, boolean writingMetadataKnown, int images) {
        if (status == null || locations == null) {
            throw new PipelineFailure(Reason.INVALID_INPUT);
        }
        if (status != StrokeLocationsStatus.COMPLETE) {
            if (!locations.isEmpty()) {
                throw new PipelineFailure(Reason.INVALID_INPUT);
            }
            return List.of();
        }
        if (!writingMetadataKnown || locations.size() > MAX_STROKE_LOCATIONS) {
            throw new PipelineFailure(Reason.INVALID_INPUT);
        }
        Set<String> locatedIds = new HashSet<>();
        for (StrokeLocation location : locations) {
            if (location == null || !locatedIds.add(location.strokeId())
                    || location.imageOrdinals().stream().anyMatch(ordinal -> ordinal >= images)) {
                throw new PipelineFailure(Reason.INVALID_INPUT);
            }
        }
        if (!locatedIds.equals(strokeIds)
                || RoleOutputValidator.JSON.writeValueAsBytes(locations).length > MAX_STROKE_LOCATIONS_JSON_BYTES) {
            throw new PipelineFailure(Reason.INVALID_INPUT);
        }
        // manifest顺序不代表语义；只规范位置表和其序号，不重排实际图片。
        return locations.stream().sorted(Comparator.comparing(StrokeLocation::strokeId))
                .map(location -> new StrokeLocation(location.strokeId(), location.worldBounds(),
                        location.imageOrdinals().stream().sorted().toList())).toList();
    }

    /** sequence在单个run内从1递增；runtime以runId+sequence保存输入摘要和已确认结果。 */
    public record Call(int sequence, Role role, String systemPrompt, String inputJson, FrozenAnswer snapshot,
            AnswerInputKind answerInputKind) {
        public Call(int sequence, Role role, String systemPrompt, String inputJson, FrozenAnswer snapshot) {
            this(sequence, role, systemPrompt, inputJson, snapshot, AnswerInputKind.SNAPSHOT);
        }

        public String inputDigest() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                List<List<String>> images = java.util.stream.IntStream.range(0, snapshot.images().size())
                        .mapToObj(index -> List.of(snapshot.images().get(index).mediaType(), imageCaption(index),
                        HexFormat.of().formatHex(digest.digest(snapshot.images().get(index).bytes())))).toList();
                return TutoringPipeline.sha256(RoleOutputValidator.JSON.writeValueAsString(Arrays.asList(
                        PROMPT_VERSION, role.name(), systemPrompt, inputJson, answerInputKind.name(), snapshot.fileRef(), images)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 unavailable");
            }
        }

        @Override
        public String toString() {
            return "RoleCall[sequence=" + sequence + ", role=" + role + "]";
        }

        public String imageCaption(int imageOrdinal) {
            return "完整冻结图块 imageOrdinal=" + imageOrdinal + "（0基，" + (imageOrdinal + 1) + "/"
                    + snapshot.images().size() + "）；世界位置与实际尺寸：" + snapshot.images().get(imageOrdinal).position();
        }
    }

    public record Usage(Integer inputTokens, Integer outputTokens, int images, boolean costKnown) {
    }

    public record Reply(String json, String model, Usage usage) {
        @Override
        public String toString() {
            return "RoleReply[model=" + model + ", usage=" + usage + "]";
        }
    }

    public record Audit(int sequence, Role role, String promptVersion, String model, Usage usage,
            long latencyMs, String inputSha256, String outputSha256) {
    }

    /** 未发布内部候选；真正发布必须由P2执行currentRunId/revision/fence与权限CAS。 */
    public record Candidate(String mode, String bodyMarkdown, List<String> evidenceIds,
            JsonNode solve, JsonNode diagnosis, JsonNode review, List<Audit> audit) {
        public Candidate {
            evidenceIds = List.copyOf(evidenceIds);
            audit = List.copyOf(audit);
            solve = solve.deepCopy();
            diagnosis = diagnosis.deepCopy();
            review = review.deepCopy();
        }

        @Override
        public String toString() {
            return "TutorCandidate[mode=" + mode + ", calls=" + audit.size() + "]";
        }
    }

    public static final class PipelineFailure extends RuntimeException {
        private final Reason reason;

        public PipelineFailure(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
