package com.betterlearning.server.agent.pipeline;

import java.util.List;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

import com.betterlearning.server.agent.model.TutorModels.FrozenAnswer;

import tools.jackson.databind.JsonNode;

/** Agent内部角色协议；不是HTTP/common DTO，不含供应商密钥或隐藏思维链。 */
public final class RoleProtocol {
    public static final String PROMPT_VERSION = "p3-roles-v3";
    public static final int MAX_GENERATION_CALLS = 8;

    private RoleProtocol() {
    }

    public enum Role {
        SOLVE, DIAGNOSE, TEACH, REVIEW
    }

    public enum Reason {
        INVALID_INPUT, INVALID_OUTPUT, MODEL_NOT_CONFIGURED, OUTCOME_UNKNOWN,
        BUDGET_EXCEEDED, REVIEW_REJECTED, EVIDENCE_REJECTED, CANCELLED
    }

    /** 必须来自已经授权且完整校验的不可变快照；全部世界坐标与块原样传给模型。 */
    public record Input(String prompt, String mode, FrozenAnswer snapshot, Set<String> strokeIds,
            Double ocrConfidence, boolean writingMetadataKnown, String ocrText) {
        public Input(String prompt, String mode, FrozenAnswer snapshot, Set<String> strokeIds, Double ocrConfidence) {
            this(prompt, mode, snapshot, strokeIds, ocrConfidence, strokeIds != null, null);
        }

        public Input {
            mode = mode == null ? "HINT" : mode;
            strokeIds = strokeIds == null ? Set.of() : Set.copyOf(strokeIds);
            if (prompt == null || prompt.isBlank() || prompt.length() > 4000
                    || (!"HINT".equals(mode) && !"FULL_SOLUTION".equals(mode))
                    || snapshot == null || snapshot.fileRef() == null || snapshot.fileRef().isBlank()
                    || snapshot.questionMarkdown() == null || snapshot.questionMarkdown().isBlank()
                    || snapshot.images().isEmpty() || (ocrText != null && ocrText.length() > 16000) || (ocrConfidence != null
                    && (!Double.isFinite(ocrConfidence) || ocrConfidence < 0 || ocrConfidence > 1))) {
                throw new PipelineFailure(Reason.INVALID_INPUT);
            }
        }

        @Override
        public String toString() {
            return "RoleInput[mode=" + mode + ", images=" + snapshot.images().size() + "]";
        }
    }

    /** sequence在单个run内从1递增；runtime以runId+sequence保存输入摘要和已确认结果。 */
    public record Call(int sequence, Role role, String systemPrompt, String inputJson, FrozenAnswer snapshot) {
        public String inputDigest() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                List<List<String>> images = snapshot.images().stream().map(image -> List.of(image.mediaType(),
                        image.position(), HexFormat.of().formatHex(digest.digest(image.bytes())))).toList();
                return TutoringPipeline.sha256(RoleOutputValidator.JSON.writeValueAsString(List.of(
                        PROMPT_VERSION, role.name(), systemPrompt, inputJson, snapshot.fileRef(), images)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 unavailable");
            }
        }

        @Override
        public String toString() {
            return "RoleCall[sequence=" + sequence + ", role=" + role + "]";
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
