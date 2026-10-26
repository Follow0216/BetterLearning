package com.betterlearning.server.agent.pipeline;

import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.TEACH;
import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.assertReason;
import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.normal;
import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import com.betterlearning.server.agent.config.AgentProperties;
import com.betterlearning.server.agent.model.TutorModels.FrozenAnswer;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Call;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Input;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Role;
import com.betterlearning.server.multiagent.ConversationContexts;
import com.betterlearning.server.multiagent.MultiAgentModels.AnswerInputKind;
import com.betterlearning.server.multiagent.MultiAgentModels.ConversationContext;
import com.betterlearning.server.multiagent.MultiAgentModels.ConversationTurn;
import com.betterlearning.server.multiagent.MultiAgentModels.Snapshot;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocationsStatus;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** 只使用固定角色输出；不请求OCR、真实模型或外部存储。 */
class NoAnswerPipelineTests {
    static final String NO_ANSWER = """
            {"status":"NO_ANSWER","summary":"本轮未提供作答快照。","observations":[],"clarification":""}
            """;

    @Test
    void noAnswerFactoryPreservesOnlyFrozenQuestionAndRejectsSnapshotMetadata() {
        Snapshot none = Snapshot.noAnswer("解方程2x+3=17");
        assertThat(none.answerInputKind()).isEqualTo(AnswerInputKind.NONE);
        assertThat(none.fileRef()).isNull();
        assertThat(none.images()).isEmpty();
        assertThat(none.strokeIds()).isEmpty();
        assertThat(none.hasHandwriting()).isNull();
        assertThat(none.ocrText()).isNull();
        assertThat(none.ocrConfidence()).isNull();
        assertThatThrownBy(() -> new Snapshot("invented-file", none.questionMarkdown(), List.of(), List.of(),
                null, null, null, StrokeLocationsStatus.UNAVAILABLE, List.of(), AnswerInputKind.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Snapshot(null, none.questionMarkdown(), List.of(), List.of(),
                false, null, null, StrokeLocationsStatus.UNAVAILABLE, List.of(), AnswerInputKind.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Snapshot(null, none.questionMarkdown(), List.of(), List.of(),
                null, "虚构OCR", null, StrokeLocationsStatus.UNAVAILABLE, List.of(), AnswerInputKind.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textOnlyParentAndAllRepairTargetsKeepAnswerKindWithoutInventingImagesOrCurrentWriting() {
        ConversationTurn parent = ConversationContextsTests.turn(1, "第二步两边同时减3，保持等式成立。");
        ConversationContext conversation = ConversationContexts.append(ConversationContexts.empty(),
                new ConversationTurn(parent.requestId(), parent.runId(), parent.resultId(), parent.mode(),
                parent.prompt(), parent.bodyMarkdown(), null, parent.replySourceId(), parent.replyVersion()));
        Input input = noAnswer(conversation);
        for (String target : List.of("NONE", "TEACH", "SOLVE", "DIAGNOSE")) {
            var boundary = new TutoringPipelineTests.Boundary(call -> {
                if (call.role() == Role.REVIEW && call.sequence() == 4 && !"NONE".equals(target)) {
                    return rejected(target);
                }
                return textOnlyOutput(call);
            });
            var candidate = new TutoringPipeline().run(input, boundary);
            assertThat(candidate.diagnosis().path("status").asString()).isEqualTo("NO_ANSWER");
            assertThat(candidate.bodyMarkdown()).contains("消去常数项").doesNotContain("补充", "笔迹", "x=7");
            assertThat(candidate.evidenceIds()).isEmpty();
            assertThat(candidate.solve().path("answer").asString()).isEqualTo("x=7");
            for (Call call : boundary.calls) {
                JsonNode envelope = RoleOutputValidator.JSON.readTree(call.inputJson());
                assertThat(call.answerInputKind()).isEqualTo(AnswerInputKind.NONE);
                assertThat(call.snapshot().fileRef()).isNull();
                assertThat(call.snapshot().images()).isEmpty();
                assertThat(call.inputDigest()).hasSize(64).isEqualTo(call.inputDigest());
                assertThat(envelope.path("answerInputKind").asString()).isEqualTo("NONE");
                assertThat(envelope.path("questionMarkdown").asString()).isEqualTo("解方程2x+3=17");
                assertThat(envelope.path("writingMetadataKnown").asBoolean()).isFalse();
                assertThat(envelope.path("strokeIds")).isEmpty();
                assertThat(envelope.path("ocrText").isNull()).isTrue();
                if (call.role() == Role.TEACH || call.role() == Role.REVIEW) {
                    assertThat(envelope.path("currentSnapshotFileRef").isNull()).isTrue();
                    assertThat(envelope.path("conversationContext").path("turns").get(0)
                            .path("snapshotFileRef").isNull()).isTrue();
                    assertThat(call.inputJson()).contains("第二步两边同时减3");
                } else {
                    assertThat(envelope.has("conversationContext")).isFalse();
                    assertThat(call.inputJson()).doesNotContain("第二步两边同时减3");
                }
            }
        }
    }

    @Test
    void noAnswerAndGenuineZeroStrokeSnapshotRequireDifferentDiagnosisStates() {
        Input noAnswer = noAnswer(ConversationContexts.empty());
        Input zeroStroke = TutoringPipelineTests.input("HINT", Set.of(), null);
        assertReason(() -> RoleOutputValidator.parse(Role.DIAGNOSE,
                TutoringPipelineTests.EMPTY_DIAGNOSIS, noAnswer), Reason.INVALID_OUTPUT);
        assertReason(() -> RoleOutputValidator.parse(Role.DIAGNOSE, NO_ANSWER, zeroStroke), Reason.INVALID_OUTPUT);
        assertReason(() -> RoleOutputValidator.parse(Role.DIAGNOSE,
                NO_ANSWER.replace("本轮未提供作答快照。", "从你的笔迹可以看出这一步有错。"), noAnswer), Reason.INVALID_OUTPUT);
        assertThat(RoleOutputValidator.parse(Role.DIAGNOSE, NO_ANSWER, noAnswer).path("clarification").asString())
                .isEmpty();
        var boundary = new TutoringPipelineTests.Boundary(TutoringPipelineTests::normal);
        var candidate = new TutoringPipeline().run(zeroStroke, boundary);
        assertThat(candidate.diagnosis().path("status").asString()).isEqualTo("NO_WRITING");
        assertThat(boundary.calls).allSatisfy(call -> {
            assertThat(call.answerInputKind()).isEqualTo(AnswerInputKind.SNAPSHOT);
            assertThat(call.snapshot().fileRef()).isNotBlank();
            assertThat(call.snapshot().images()).isNotEmpty();
        });
        assertReason(() -> new Input(noAnswer.prompt(), noAnswer.mode(), noAnswer.snapshot(), Set.of(),
                null, true, null, StrokeLocationsStatus.UNAVAILABLE, List.of(), ConversationContexts.empty(),
                AnswerInputKind.NONE), Reason.INVALID_INPUT);
        assertReason(() -> new Input(noAnswer.prompt(), noAnswer.mode(), zeroStroke.snapshot(), Set.of(),
                null, false, null, StrokeLocationsStatus.UNAVAILABLE, List.of(), ConversationContexts.empty(),
                AnswerInputKind.NONE), Reason.INVALID_INPUT);
    }

    @Test
    void inventedCurrentHandwritingOrBlankSubmissionIsRepairedOrWithheldEvenWhenReviewApproves() {
        for (String falseClaim : List.of("从你的笔迹可以看出，这一步保持了等式。", "你还没有写，请先补充作答。")) {
            for (boolean repairSucceeds : List.of(true, false)) {
                var boundary = new TutoringPipelineTests.Boundary(call -> call.role() == Role.TEACH
                        && (call.sequence() == 3 || !repairSucceeds)
                        ? TEACH.replace("先考虑怎样消去常数项。", falseClaim) : textOnlyOutput(call));
                if (repairSucceeds) {
                    var candidate = new TutoringPipeline().run(noAnswer(ConversationContexts.empty()), boundary);
                    assertThat(candidate.bodyMarkdown()).doesNotContain(falseClaim);
                } else {
                    assertReason(() -> new TutoringPipeline().run(noAnswer(ConversationContexts.empty()), boundary),
                            Reason.REVIEW_REJECTED);
                }
                assertThat(boundary.calls).hasSize(6);
                assertThat(boundary.calls.get(4).inputJson())
                        .contains("UNSUPPORTED_CURRENT_ANSWER_OBSERVATION", "previousCandidate", falseClaim);
                assertThat(boundary.calls.get(4).answerInputKind()).isEqualTo(AnswerInputKind.NONE);
            }
        }
    }

    @Test
    void textOnlyInputDigestIncludesFrozenQuestionWithoutNullSubstitution() {
        Input input = noAnswer(ConversationContexts.empty());
        var boundary = new TutoringPipelineTests.Boundary(NoAnswerPipelineTests::textOnlyOutput);
        new TutoringPipeline().run(input, boundary);
        Call original = boundary.calls.get(0);
        Call changedPayload = new Call(original.sequence(), original.role(), original.systemPrompt(),
                original.inputJson().replace("解方程2x+3=17", "其他冻结题面"),
                new FrozenAnswer(null, "其他冻结题面", List.of()), AnswerInputKind.NONE);
        assertThat(changedPayload.inputDigest()).isNotEqualTo(original.inputDigest());
        assertThat(original.snapshot().fileRef()).isNull();
    }

    @Test
    void textOnlyProviderBoundaryAcceptsZeroImagesBeforeReachingConfigurationValidation() {
        Input input = noAnswer(ConversationContexts.empty());
        LangChain4jRoleModel model = new LangChain4jRoleModel(new AgentProperties().getTutoring());
        Call textOnly = new Call(1, Role.SOLVE, RolePrompts.forRole(Role.SOLVE), "{}", input.snapshot(), AnswerInputKind.NONE);
        assertReason(() -> model.generate(textOnly), Reason.MODEL_NOT_CONFIGURED);
        Call invalidSnapshot = new Call(1, Role.SOLVE, RolePrompts.forRole(Role.SOLVE), "{}", input.snapshot());
        assertReason(() -> model.generate(invalidSnapshot), Reason.INVALID_INPUT);
    }

    private static Input noAnswer(ConversationContext context) {
        return new Input("你刚才第二步为什么这样做？", "HINT", new FrozenAnswer(null, "解方程2x+3=17", List.of()),
                Set.of(), null, false, null, StrokeLocationsStatus.UNAVAILABLE, List.of(), context, AnswerInputKind.NONE);
    }

    private static String textOnlyOutput(Call call) {
        return call.role() == Role.DIAGNOSE ? NO_ANSWER : normal(call);
    }
}
