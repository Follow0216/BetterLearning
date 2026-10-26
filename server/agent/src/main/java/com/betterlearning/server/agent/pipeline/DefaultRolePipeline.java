package com.betterlearning.server.agent.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.betterlearning.server.agent.config.AgentProperties;
import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.model.TutorModels.FrozenAnswer;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Call;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Input;
import com.betterlearning.server.agent.pipeline.RoleProtocol.PipelineFailure;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reply;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Usage;
import com.betterlearning.server.multiagent.EvidenceRetriever;
import com.betterlearning.server.multiagent.MultiAgentModels;
import com.betterlearning.server.multiagent.MultiAgentModels.Evidence;
import com.betterlearning.server.multiagent.MultiAgentModels.PipelineContext;
import com.betterlearning.server.multiagent.MultiAgentModels.PipelineResult;
import com.betterlearning.server.multiagent.MultiAgentModels.RetrievalQuery;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.StepInvocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StepOutput;
import com.betterlearning.server.multiagent.RolePipeline;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.StepExecutor;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.multiagent.TutorLedger;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** P1公共端口到P3内部协议的装配；模型、检索均经P2逐步执行器，绝不自行发布。 */
@Component
public final class DefaultRolePipeline implements RolePipeline {
    private final AgentProperties properties;
    private final ObjectProvider<TutorLedger> ledgers;
    private final ObjectProvider<SourceStore> sources;
    private final ObjectProvider<EvidenceRetriever> retrievers;

    public DefaultRolePipeline(AgentProperties properties, ObjectProvider<TutorLedger> ledgers,
            ObjectProvider<SourceStore> sources, ObjectProvider<EvidenceRetriever> retrievers) {
        this.properties = properties;
        this.ledgers = ledgers;
        this.sources = sources;
        this.retrievers = retrievers;
    }

    @Override
    public PipelineResult execute(PipelineContext context, StepExecutor steps) {
        TutorLedger ledger = ledgers.getIfAvailable();
        if (ledger == null || context == null || context.execution() == null || context.snapshot() == null
                || steps == null) {
            return failure(Reason.INVALID_INPUT);
        }
        var execution = context.execution();
        var snapshot = context.snapshot();
        if (execution.snapshotFileRef() == null || !execution.snapshotFileRef().equals(snapshot.fileRef())) {
            return failure(Reason.INVALID_INPUT);
        }
        try {
            // 在创建模型图片副本和任何step/attempt前预检旧快照；不裁剪后继续执行。
            TutoringInputBudget.validateSnapshot(snapshot);
            List<ModelImage> images = snapshot.images().stream()
                    .map(image -> new ModelImage(image.mediaType(), image.bytes(), image.position())).toList();
            boolean known = snapshot.hasHandwriting() != null;
            if (known && (snapshot.strokeIds() == null
                    || snapshot.hasHandwriting() != !snapshot.strokeIds().isEmpty())) {
                return failure(Reason.INVALID_INPUT);
            }
            Set<String> strokeIds = snapshot.strokeIds() == null ? Set.of() : Set.copyOf(snapshot.strokeIds());
            Input input = new Input(execution.prompt(), execution.mode() == null ? "HINT" : execution.mode().name(),
                    new FrozenAnswer(snapshot.fileRef(), snapshot.questionMarkdown(), images), strokeIds,
                    snapshot.ocrConfidence(), known, snapshot.ocrText(),
                    snapshot.strokeLocationsStatus(), snapshot.strokeLocations(), execution.conversationContext());
            LangChain4jRoleModel model = new LangChain4jRoleModel(properties.getTutoring());
            RuntimeBoundary boundary = new RuntimeBoundary(context, steps, ledger, model, input);
            if (input.writingMetadataKnown() && TutoringInputBudget.locationBudgetExceeded(input)
                    && execution.modelCalls() == 0 && boundary.activeExecution().modelCalls() == 0) {
                // 仅新运行早止；已有调用仍走原执行器复用/UNKNOWN守卫。未伪造任何模型角色输出。
                // verified只表示固定说明经程序校验，发布仍由P2/P1执行权限与状态CAS。
                return new PipelineResult(true, TutoringInputBudget.LOCATION_LIMIT_CLARIFICATION, List.of(), null);
            }
            var candidate = new TutoringPipeline().run(input, boundary);
            return new PipelineResult(true, candidate.bodyMarkdown(), candidate.evidenceIds(), null);
        } catch (PipelineFailure exception) {
            return failure(exception.reason());
        } catch (StepExecutionException exception) {
            String reason = Set.of("OUTCOME_UNKNOWN", "BUDGET_EXCEEDED", "MODEL_UNAVAILABLE", "RUN_TIMEOUT")
                    .contains(exception.reason()) ? exception.reason() : "REVIEW_REJECTED";
            return new PipelineResult(false, null, List.of(), reason);
        }
    }

    private static PipelineResult failure(Reason reason) {
        String externalReason = switch (reason) {
            case OUTCOME_UNKNOWN -> "OUTCOME_UNKNOWN";
            case BUDGET_EXCEEDED, MODEL_INPUT_TOO_LARGE -> "BUDGET_EXCEEDED";
            case MODEL_NOT_CONFIGURED -> "MODEL_UNAVAILABLE";
            default -> "REVIEW_REJECTED";
        };
        return new PipelineResult(false, null, List.of(), externalReason);
    }

    /** 持久化的只有通过schema的结构化结论；未知字段和原始供应商错误不进入账本。 */
    public record StoredReply(String output, String failure, String model, Usage usage) {
    }

    public record StoredRetrieval(List<Evidence> evidence) {
    }

    private final class RuntimeBoundary implements GenerationBoundary {
        private final PipelineContext context;
        private final StepExecutor steps;
        private final TutorLedger ledger;
        private final LangChain4jRoleModel model;
        private final Input input;
        private final Map<String, Evidence> evidenceById = new LinkedHashMap<>();
        private final Map<String, String> sourceTypes = new LinkedHashMap<>();
        private final Map<String, String> textOrigins = new LinkedHashMap<>();
        private final Map<String, String> answerStates = new LinkedHashMap<>();

        private RuntimeBoundary(PipelineContext context, StepExecutor steps, TutorLedger ledger,
                LangChain4jRoleModel model, Input input) {
            this.context = context;
            this.steps = steps;
            this.ledger = ledger;
            this.model = model;
            this.input = input;
        }

        @Override
        public Reply generate(Call call) {
            // 已确认输出由executor直接复用，不执行action；配置错误记录安全失败，不调用供应商。
            StepInvocation invocation = new StepInvocation(MultiAgentModels.Role.valueOf(call.role().name()),
                    call.sequence(), call.inputDigest());
            StepOutput stored = steps.execute(invocation, () -> {
                StoredReply safe;
                try {
                    model.validateConfiguration();
                    checkActive();
                    Reply reply = model.generate(call);
                    JsonNode validated = RoleOutputValidator.parse(call.role(), reply.json(), input);
                    safe = new StoredReply(RoleOutputValidator.JSON.writeValueAsString(validated), null,
                            reply.model(), reply.usage());
                } catch (PipelineFailure exception) {
                    if (exception.reason() == Reason.OUTCOME_UNKNOWN) {
                        throw new StepExecutionException("OUTCOME_UNKNOWN");
                    }
                    // 已响应但schema无效也确认消费，不保存其原始响应，不再收费重试。
                    safe = new StoredReply(null, exception.reason().name(), null, null);
                }
                return stored(safe);
            });
            checkDigest(stored);
            StoredReply reply;
            try {
                reply = RoleOutputValidator.JSON.readValue(stored.output(), StoredReply.class);
            } catch (RuntimeException exception) {
                throw new PipelineFailure(Reason.INVALID_OUTPUT);
            }
            if (reply.failure() != null) {
                try {
                    throw new PipelineFailure(Reason.valueOf(reply.failure()));
                } catch (IllegalArgumentException exception) {
                    throw new PipelineFailure(Reason.INVALID_OUTPUT);
                }
            }
            return new Reply(reply.output(), reply.model(), reply.usage());
        }

        @Override
        public void checkActive() {
            activeExecution();
        }

        private MultiAgentModels.Execution activeExecution() {
            var original = context.execution();
            var current = ledger.execution(original.accountId(), original.runId());
            if (current == null || current.status() != MultiAgentModels.Status.RUNNING
                    || current.cancelRequestedAt() != null || !current.requestId().equals(original.requestId())
                    || (current.runDeadlineAt() != null && !current.runDeadlineAt().isAfter(Instant.now()))) {
                throw new PipelineFailure(Reason.CANCELLED);
            }
            return current;
        }

        @Override
        public List<JsonNode> retrieve(List<String> methodTags) {
            var execution = context.execution();
            RetrievalQuery query = new RetrievalQuery(execution.accountId(), execution.runId(), execution.questionId(),
                    input.snapshot().questionMarkdown() + "\n" + input.prompt(), methodTags, 5);
            String digest = TutoringPipeline.sha256(RoleOutputValidator.JSON.writeValueAsString(query));
            StepOutput saved = steps.execute(new StepInvocation(MultiAgentModels.Role.RETRIEVE, 1, digest), () -> {
                EvidenceRetriever retriever = retrievers.getIfAvailable();
                List<Evidence> items = context.evidence() != null && !context.evidence().isEmpty()
                        ? context.evidence() : retriever == null ? List.of() : retriever.retrieve(query);
                return stored(new StoredRetrieval(items));
            });
            checkDigest(saved);
            List<Evidence> items;
            try {
                items = RoleOutputValidator.JSON.readValue(saved.output(), StoredRetrieval.class).evidence();
            } catch (RuntimeException exception) {
                throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
            }
            List<JsonNode> projected = new ArrayList<>();
            if (items == null || items.size() > 5) {
                throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
            }
            for (Evidence item : items) {
                if (item == null || !execution.runId().equals(item.runId())
                        || evidenceById.putIfAbsent(item.evidenceId(), item) != null) {
                    throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
                }
                projected.add(RoleOutputValidator.JSON.valueToTree(item));
            }
            RoleOutputValidator.evidence(projected);
            if (!authorizeEvidence(evidenceById.keySet())) {
                throw new PipelineFailure(Reason.EVIDENCE_REJECTED);
            }
            for (JsonNode node : projected) {
                var object = (tools.jackson.databind.node.ObjectNode) node;
                String id = node.get("evidenceId").asString();
                object.put("sourceType", sourceTypes.get(id));
                object.put("textOrigin", textOrigins.get(id));
                object.put("answerState", answerStates.get(id));
            }
            ledger.recordEvidence(execution.accountId(), execution.runId(), List.copyOf(items));
            return projected;
        }

        @Override
        public boolean authorizeEvidence(Set<String> ids) {
            if (ids.isEmpty()) {
                return true;
            }
            SourceStore store = sources.getIfAvailable();
            if (store == null) {
                return false;
            }
            for (String id : ids) {
                Evidence evidence = evidenceById.get(id);
                if (evidence == null || evidence.sourceId() == null || evidence.version() == null
                        || evidence.anchorId() == null || !"HISTORY".equals(evidence.type())) {
                    return false;
                }
                SourceContent content;
                try {
                    UUID.fromString(evidence.evidenceId());
                    content = store.get(context.execution().accountId(), evidence.sourceId(), evidence.version());
                } catch (RuntimeException exception) {
                    return false;
                }
                if (content == null || content.source() == null || content.version() == null
                        || content.source().accountId() != context.execution().accountId()
                        || !content.source().sourceId().equals(evidence.sourceId())
                        || !"ACTIVE".equals(content.source().status())
                        || content.source().type() == MultiAgentModels.SourceType.TUTOR_SNAPSHOT
                        || content.source().currentVersion() != evidence.version()
                        || !content.version().sourceId().equals(evidence.sourceId())
                        || content.version().version() != evidence.version()
                        || !"READY".equals(content.version().status()) || content.anchors() == null
                        || content.anchors().stream().noneMatch(anchor -> anchor.anchorId().equals(evidence.anchorId())
                        && anchor.sourceId().equals(evidence.sourceId()) && anchor.version() == evidence.version()
                        && anchor.text() != null && anchor.text().equals(evidence.claim()))) {
                    return false;
                }
                String answerState = content.source().type() == MultiAgentModels.SourceType.SAVED_ANSWER
                        ? content.version().answerState() : null;
                if (answerState != null && !Set.of("DRAFT", "COMPLETED").contains(answerState)) {
                    return false;
                }
                sourceTypes.put(id, content.source().type().name());
                answerStates.put(id, answerState);
                content.anchors().stream().filter(anchor -> anchor.anchorId().equals(evidence.anchorId()))
                        .findFirst().ifPresent(anchor -> textOrigins.put(id, anchor.textOrigin()));
            }
            return true;
        }
    }

    private static StepOutput stored(Object value) {
        String json = RoleOutputValidator.JSON.writeValueAsString(value);
        return new StepOutput(json, TutoringPipeline.sha256(json));
    }

    private static void checkDigest(StepOutput output) {
        if (output == null || output.output() == null
                || !TutoringPipeline.sha256(output.output()).equals(output.outputDigest())) {
            throw new PipelineFailure(Reason.INVALID_OUTPUT);
        }
    }
}
