package com.betterlearning.server.agent.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.betterlearning.server.agent.config.AgentProperties;
import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.model.TutorModels.FrozenAnswer;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Call;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Input;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reply;
import com.betterlearning.server.agent.pipeline.RoleProtocol.PipelineFailure;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Usage;
import com.betterlearning.server.multiagent.CitationMarkers;
import com.betterlearning.server.multiagent.EvidenceRetriever;
import com.betterlearning.server.multiagent.MultiAgentModels;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.Evidence;
import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.Image;
import com.betterlearning.server.multiagent.MultiAgentModels.PipelineContext;
import com.betterlearning.server.multiagent.MultiAgentModels.Role;
import com.betterlearning.server.multiagent.MultiAgentModels.Source;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceVersion;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocationsStatus;
import com.betterlearning.server.multiagent.MultiAgentModels.WorldBounds;
import com.betterlearning.server.multiagent.MultiAgentModels.StepInvocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StepOutput;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.StepExecutor;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.multiagent.TutorLedger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import tools.jackson.databind.JsonNode;

/** 公共端口适配测试：CONFIRMED替身直接返回安全JSON，绝不请求真实供应商。 */
class DefaultRolePipelineTests {
    private static final String RUN = "6e24f468-4f85-4f44-9b29-b12621793cdf";
    private static final String REQUEST = "54c37a37-0be0-4673-a7f2-acaf68cd1d62";
    private static final String SOURCE = "a175236f-aa91-4aa2-8d60-323e1470b38a";
    private static final String ANCHOR = "826d6e9a-ce49-46b9-97b8-d71145a2b25d";
    private static final String EVIDENCE = TutoringPipelineTests.EVIDENCE;
    private static final String CITATION = "94719ee6-32bc-4d91-8471-63add0d0c6ab";
    private static final String UNCERTAIN_DIAGNOSIS = """
            {"status":"UNCERTAIN","summary":"当前协议未提供笔划元数据","observations":[],
            "clarification":"请确认你希望检查的笔迹步骤。"}
            """;
    private static final String CLARIFYING_TEACH = TutoringPipelineTests.TEACH.replace(
            "\"clarification\":\"\"", "\"clarification\":\"请确认你希望检查的笔迹步骤。\"");

    private final TutorLedger ledger = mock(TutorLedger.class);
    private final SourceStore sources = mock(SourceStore.class);
    private final EvidenceRetriever retriever = mock(EvidenceRetriever.class);
    private final DefaultRolePipeline pipeline = new DefaultRolePipeline(new AgentProperties(),
            provider(ledger), provider(sources), provider(retriever));

    @Test
    void textOnlyConfirmedReplayUsesFrozenQuestionWithoutSnapshotEvidenceOrProviderCalls() {
        Execution execution = textOnlyExecution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        PipelineContext context = new PipelineContext(execution,
                MultiAgentModels.Snapshot.noAnswer(execution.frozenQuestionMarkdown()), List.of());
        ReplaySteps steps = emptyHistorySteps().reply(Role.DIAGNOSE, NoAnswerPipelineTests.NO_ANSWER)
                .reply(Role.TEACH, TutoringPipelineTests.TEACH);
        var first = pipeline.execute(context, steps);
        assertThat(first.verified()).isTrue();
        assertThat(first.evidenceIds()).isEmpty();
        assertThat(first.bodyMarkdown()).contains("消去常数项").doesNotContain("作答图", "补充", "笔迹");
        assertThat(pipeline.execute(context, steps)).isEqualTo(first);
        assertThat(steps.actionCalls).isZero();
        assertThat(steps.invocations).allSatisfy(call -> assertThat(call.inputDigest()).hasSize(64));
        verifyNoInteractions(sources, retriever);
    }

    @Test
    void textOnlyUnknownAndCancellationStillStopWithoutAnotherModelAction() {
        Execution running = textOnlyExecution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(running);
        PipelineContext context = new PipelineContext(running,
                MultiAgentModels.Snapshot.noAnswer(running.frozenQuestionMarkdown()), List.of());
        ReplaySteps unknown = emptyHistorySteps();
        unknown.unknown = Role.SOLVE;
        assertThat(pipeline.execute(context, unknown).reason()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(unknown.invocations).extracting(StepInvocation::role).containsExactly(Role.SOLVE);
        assertThat(unknown.actionCalls).isZero();

        when(ledger.execution(42L, RUN)).thenReturn(running, textOnlyExecution(MultiAgentModels.Status.CANCELLED, Instant.now()));
        ReplaySteps cancelled = emptyHistorySteps();
        assertThat(pipeline.execute(context, cancelled).verified()).isFalse();
        assertThat(cancelled.invocations).extracting(StepInvocation::role).containsExactly(Role.SOLVE);
        assertThat(cancelled.actionCalls).isZero();
    }

    @Test
    void noAnswerCannotBindAnExistingSnapshotOrDifferentFrozenQuestion() {
        Execution textOnly = textOnlyExecution(MultiAgentModels.Status.RUNNING, null);
        for (PipelineContext context : List.of(
                new PipelineContext(execution(MultiAgentModels.Status.RUNNING, null),
                MultiAgentModels.Snapshot.noAnswer(textOnly.frozenQuestionMarkdown()), List.of()),
                new PipelineContext(textOnly, MultiAgentModels.Snapshot.noAnswer("其他题目"), List.of()))) {
            ReplaySteps steps = emptyHistorySteps();
            assertThat(pipeline.execute(context, steps).verified()).isFalse();
            assertThat(steps.invocations).isEmpty();
        }
        verifyNoInteractions(ledger, sources, retriever);
    }

    private static Execution textOnlyExecution(MultiAgentModels.Status status, Instant cancel) {
        return new Execution(42L, REQUEST, "17", RUN, MultiAgentModels.Mode.HINT,
                "请给下一步提示。", null, null, null, status, 1L, null, Instant.now().plusSeconds(300), cancel, 0,
                com.betterlearning.server.multiagent.ConversationContexts.empty(), "解方程2x+3=17");
    }

    @Test
    void executionFrozenConversationReachesModelInputDigestsAndConfirmedReplayWithoutProviderCalls() {
        Execution original = execution(MultiAgentModels.Status.RUNNING, null);
        var conversation = com.betterlearning.server.multiagent.ConversationContexts.append(
                com.betterlearning.server.multiagent.ConversationContexts.empty(),
                ConversationContextsTests.turn(1, "第二步两边同时减3，继续解释等式性质。"));
        Execution execution = new Execution(original.accountId(), original.requestId(), original.questionId(), original.runId(),
                original.mode(), "你刚才第二步为什么这样做？", original.snapshotFileRef(), original.snapshotSourceId(),
                original.snapshotVersion(), original.status(), original.revision(), original.queueDeadlineAt(),
                original.runDeadlineAt(), null, 0, conversation);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        PipelineContext context = knownEmptyContext(execution);
        var images = context.snapshot().images().stream()
                .map(image -> new ModelImage(image.mediaType(), image.bytes(), image.position())).toList();
        Input input = new Input(execution.prompt(), "HINT", new FrozenAnswer(context.snapshot().fileRef(),
                context.snapshot().questionMarkdown(), images), Set.of(), null, true, null,
                context.snapshot().strokeLocationsStatus(), context.snapshot().strokeLocations(), conversation);
        TutoringPipelineTests.Boundary expected = new TutoringPipelineTests.Boundary(TutoringPipelineTests::normal);
        new TutoringPipeline().run(input, expected);
        ReplaySteps steps = emptyHistorySteps().reply(Role.DIAGNOSE, TutoringPipelineTests.EMPTY_DIAGNOSIS)
                .reply(Role.TEACH, TutoringPipelineTests.TEACH);
        assertThat(pipeline.execute(context, steps).verified()).isTrue();
        assertThat(pipeline.execute(context, steps).verified()).isTrue();
        assertThat(steps.actionCalls).isZero();
        for (Call call : expected.calls) {
            assertThat(steps.invocations.stream().filter(invocation -> invocation.role().name().equals(call.role().name()))
                    .map(StepInvocation::inputDigest).toList()).containsExactly(call.inputDigest(), call.inputDigest());
            if (call.role() == RoleProtocol.Role.TEACH || call.role() == RoleProtocol.Role.REVIEW) {
                assertThat(call.inputJson()).contains("第二步两边同时减3", "conversationContext");
            } else {
                assertThat(call.inputJson()).doesNotContain("第二步两边同时减3", "conversationContext");
            }
        }
    }

    @Test
    void confirmedReplayNeedsNoProviderConfigurationOrActionsAndRetainsClarification() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        ReplaySteps steps = emptyHistorySteps();

        var first = pipeline.execute(context(execution), steps);
        var replay = pipeline.execute(context(execution), steps);

        assertThat(first.verified()).isTrue();
        assertThat(replay).isEqualTo(first);
        assertThat(first.bodyMarkdown()).contains("请说明希望检查的那一步")
                .doesNotContain("x=7");
        assertThat(first.evidenceIds()).isEmpty();
        assertThat(steps.actionCalls).isZero();
        assertThat(steps.invocations).extracting(StepInvocation::role).containsExactly(
                Role.SOLVE, Role.DIAGNOSE, Role.RETRIEVE, Role.TEACH, Role.REVIEW,
                Role.SOLVE, Role.DIAGNOSE, Role.RETRIEVE, Role.TEACH, Role.REVIEW);
        assertThat(steps.invocations).allSatisfy(call -> assertThat(call.inputDigest()).hasSize(64));
        verify(ledger, atLeast(2)).recordEvidence(eq(42L), eq(RUN), eq(List.of()));
        verifyNoInteractions(retriever, sources);
    }

    @Test
    void damagedStoredDigestStopsBeforeAnyOtherRoleAndDoesNotCallProvider() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        ReplaySteps steps = emptyHistorySteps();
        steps.corrupt = Role.SOLVE;

        var result = pipeline.execute(context(execution), steps);

        assertThat(result.verified()).isFalse();
        assertThat(result.reason()).isEqualTo("REVIEW_REJECTED");
        assertThat(steps.invocations).extracting(StepInvocation::role).containsExactly(Role.SOLVE);
        assertThat(steps.actionCalls).isZero();
        verifyNoInteractions(retriever, sources);
    }

    @Test
    void unknownCallIntentStopsRunWithoutRetrievalOrAnotherAction() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        ReplaySteps steps = emptyHistorySteps();
        steps.unknown = Role.DIAGNOSE;

        var result = pipeline.execute(context(execution), steps);

        assertThat(result.verified()).isFalse();
        assertThat(result.reason()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(steps.invocations).extracting(StepInvocation::role)
                .containsExactly(Role.SOLVE, Role.DIAGNOSE);
        assertThat(steps.actionCalls).isZero();
        verifyNoInteractions(retriever, sources);
    }

    @Test
    void confirmedRetrievalIsReauthorizedAndWithdrawnSourceNeverReachesTeaching() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        when(sources.get(42L, SOURCE, 1L)).thenReturn(sourceContent("DELETED", 1, "READY"));
        ReplaySteps steps = historySteps(markerTeach());

        var result = pipeline.execute(knownEmptyContext(execution), steps);

        assertThat(result.verified()).isFalse();
        assertThat(steps.invocations).extracting(StepInvocation::role)
                .containsExactly(Role.SOLVE, Role.DIAGNOSE, Role.RETRIEVE);
        assertThat(steps.actionCalls).isZero();
        verify(ledger, never()).recordEvidence(eq(42L), eq(RUN), anyList());
    }

    @Test
    void currentReadyHistoryUsesEvidenceMarkerForP1CitationResolution() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        when(sources.get(42L, SOURCE, 1L)).thenReturn(sourceContent("ACTIVE", 1, "READY"));
        ReplaySteps steps = historySteps(markerTeach());

        var result = pipeline.execute(knownEmptyContext(execution), steps);

        assertThat(result.verified()).isTrue();
        assertThat(result.evidenceIds()).containsExactly(EVIDENCE);
        assertThat(result.bodyMarkdown()).contains("[[evidence:" + EVIDENCE + "]]")
                .doesNotContain("[[citation:");
        assertThat(CitationMarkers.resolve(result.bodyMarkdown(), Map.of(EVIDENCE, CITATION)))
                .contains("[[citation:" + CITATION + "]]").doesNotContain("[[evidence:");
        assertThat(steps.actionCalls).isZero();
        verify(sources, atLeast(2)).get(42L, SOURCE, 1L);
        verify(ledger).recordEvidence(eq(42L), eq(RUN), eq(List.of(historyEvidence())));
    }

    @Test
    void authorizedAnswerStateIsProjectedSeparatelyWithoutChangingHistoricalClaim() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        for (var scenario : List.of(new AnswerStateScenario(SourceType.SAVED_ANSWER, "DRAFT"),
                new AnswerStateScenario(SourceType.SAVED_ANSWER, "COMPLETED"),
                new AnswerStateScenario(SourceType.NOTE, null))) {
            when(sources.get(42L, SOURCE, 1L)).thenReturn(
                    sourceContent(scenario.sourceType(), scenario.answerState()));
            PipelineContext context = knownEmptyContext(execution);
            ReplaySteps steps = historySteps(markerTeach());

            var result = pipeline.execute(context, steps);

            assertThat(result.verified()).isTrue();
            assertThat(result.evidenceIds()).containsExactly(EVIDENCE);
            assertThat(result.bodyMarkdown()).contains("[[evidence:" + EVIDENCE + "]]");
            assertThat(steps.actionCalls).isZero();
            Map<RoleProtocol.Role, Call> expectedCalls = expectedCallsWithAnswerState(
                    execution, context.snapshot(), scenario);
            for (RoleProtocol.Role role : List.of(RoleProtocol.Role.TEACH, RoleProtocol.Role.REVIEW)) {
                String adapterDigest = steps.invocations.stream()
                        .filter(invocation -> invocation.role().name().equals(role.name()))
                        .findFirst().orElseThrow().inputDigest();
                Call expected = expectedCalls.get(role);
                assertThat(adapterDigest).as("%s %s projected input", scenario.answerState(), role)
                        .isEqualTo(expected.inputDigest());
                JsonNode projected = RoleOutputValidator.JSON.readTree(expected.inputJson())
                        .path("context").path("allowedEvidence").get(0);
                assertThat(projected.path("claim").asString()).isEqualTo(historyEvidence().claim());
                assertThat(projected.path("sourceType").asString())
                        .isEqualTo(scenario.sourceType().name());
                assertThat(projected.path("textOrigin").asString()).isEqualTo(
                        scenario.sourceType() == SourceType.SAVED_ANSWER ? "OCR_DERIVED" : "SOURCE_TEXT");
                assertThat(expected.systemPrompt()).contains("DRAFT", "COMPLETED", "草稿", "非权威");
                if (scenario.answerState() == null) {
                    assertThat(projected.get("answerState").isNull()).isTrue();
                } else {
                    assertThat(projected.path("answerState").asString())
                            .isEqualTo(scenario.answerState());
                }
            }
        }
    }

    @Test
    void invalidSavedAnswerStateNeverReachesTeachingAndNonAnswerStateStaysNull() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        when(sources.get(42L, SOURCE, 1L)).thenReturn(sourceContent(SourceType.SAVED_ANSWER, "UNTRUSTED"));
        ReplaySteps invalid = historySteps(markerTeach());
        assertThat(pipeline.execute(knownEmptyContext(execution), invalid).verified()).isFalse();
        assertThat(invalid.invocations).extracting(StepInvocation::role)
                .containsExactly(Role.SOLVE, Role.DIAGNOSE, Role.RETRIEVE);
        verify(ledger, never()).recordEvidence(eq(42L), eq(RUN), anyList());

        when(sources.get(42L, SOURCE, 1L)).thenReturn(sourceContent(SourceType.NOTE, "COMPLETED"));
        PipelineContext context = knownEmptyContext(execution);
        ReplaySteps note = historySteps(markerTeach());
        assertThat(pipeline.execute(context, note).verified()).isTrue();
        var expected = expectedCallsWithAnswerState(execution, context.snapshot(),
                new AnswerStateScenario(SourceType.NOTE, null));
        for (RoleProtocol.Role role : List.of(RoleProtocol.Role.TEACH, RoleProtocol.Role.REVIEW)) {
            assertThat(note.invocations.stream().filter(item -> item.role().name().equals(role.name()))
                    .findFirst().orElseThrow().inputDigest()).isEqualTo(expected.get(role).inputDigest());
        }
    }

    private static Map<RoleProtocol.Role, Call> expectedCallsWithAnswerState(Execution execution,
            MultiAgentModels.Snapshot snapshot, AnswerStateScenario scenario) {
        var imageData = snapshot.images().stream()
                .map(image -> new ModelImage(image.mediaType(), image.bytes(), image.position())).toList();
        Input input = new Input(execution.prompt(), execution.mode().name(),
                new FrozenAnswer(snapshot.fileRef(), snapshot.questionMarkdown(), imageData),
                Set.of(), null, true, null, StrokeLocationsStatus.UNAVAILABLE, List.of());
        var projected = (tools.jackson.databind.node.ObjectNode) RoleOutputValidator.JSON.valueToTree(historyEvidence());
        projected.put("sourceType", scenario.sourceType().name());
        projected.put("textOrigin", scenario.sourceType() == SourceType.SAVED_ANSWER ? "OCR_DERIVED" : "SOURCE_TEXT");
        if (scenario.answerState() == null) {
            projected.putNull("answerState");
        } else {
            projected.put("answerState", scenario.answerState());
        }
        Map<RoleProtocol.Role, Call> calls = new EnumMap<>(RoleProtocol.Role.class);
        new TutoringPipeline().run(input, new GenerationBoundary() {
            @Override
            public Reply generate(Call call) {
                calls.put(call.role(), call);
                String output = switch (call.role()) {
                    case SOLVE -> TutoringPipelineTests.SOLVE;
                    case DIAGNOSE -> TutoringPipelineTests.EMPTY_DIAGNOSIS;
                    case TEACH -> markerTeach();
                    case REVIEW -> TutoringPipelineTests.REVIEW;
                };
                return new Reply(output, "expected-fixture", new Usage(15, 10, 1, false));
            }

            @Override
            public void checkActive() {
            }

            @Override
            public List<JsonNode> retrieve(List<String> methodTags) {
                return List.of(projected);
            }

            @Override
            public boolean authorizeEvidence(Set<String> ids) {
                return ids.isEmpty() || ids.equals(Set.of(EVIDENCE));
            }
        });
        return calls;
    }

    private record AnswerStateScenario(SourceType sourceType, String answerState) { }

    @Test
    void evidenceIdWithoutBodyMarkerIsWithheldEvenWhenReviewSaysApproved() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        when(sources.get(42L, SOURCE, 1L)).thenReturn(sourceContent("ACTIVE", 1, "READY"));
        String noMarker = CLARIFYING_TEACH.replace("\"evidenceIds\":[]",
                "\"evidenceIds\":[\"" + EVIDENCE + "\"]");
        ReplaySteps steps = historySteps(noMarker);

        var result = pipeline.execute(knownEmptyContext(execution), steps);

        assertThat(result.verified()).isFalse();
        assertThat(result.reason()).isEqualTo("REVIEW_REJECTED");
        assertThat(steps.actionCalls).isZero();
        assertThat(steps.invocations).extracting(StepInvocation::role)
                .containsExactly(Role.SOLVE, Role.DIAGNOSE, Role.RETRIEVE,
                Role.TEACH, Role.REVIEW, Role.TEACH, Role.REVIEW);
    }

    @Test
    void sourceRevokedAfterReviewCannotProduceVerifiedResult() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        when(sources.get(42L, SOURCE, 1L)).thenReturn(sourceContent("ACTIVE", 1, "READY"),
                sourceContent("ACTIVE", 1, "READY"), sourceContent("DELETED", 1, "READY"));
        ReplaySteps steps = historySteps(markerTeach());

        var result = pipeline.execute(knownEmptyContext(execution), steps);

        assertThat(result.verified()).isFalse();
        assertThat(steps.actionCalls).isZero();
        assertThat(steps.invocations).extracting(StepInvocation::role)
                .containsExactly(Role.SOLVE, Role.DIAGNOSE, Role.RETRIEVE, Role.TEACH, Role.REVIEW);
        verify(sources, atLeast(3)).get(42L, SOURCE, 1L);
    }

    @Test
    void cancellationObservedAfterConfirmedSolveStopsBeforeDiagnosis() {
        Execution running = execution(MultiAgentModels.Status.RUNNING, null);
        Execution cancelled = execution(MultiAgentModels.Status.CANCELLED, Instant.now());
        when(ledger.execution(42L, RUN)).thenReturn(running, cancelled);
        ReplaySteps steps = emptyHistorySteps();

        var result = pipeline.execute(context(running), steps);

        assertThat(result.verified()).isFalse();
        assertThat(steps.invocations).extracting(StepInvocation::role).containsExactly(Role.SOLVE);
        assertThat(steps.actionCalls).isZero();
        verifyNoInteractions(retriever, sources);
    }

    @Test
    void completeStrokeLocationsReachDiagnosisAndChangeDurableStepDigest() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        var original = context(execution).snapshot();
        String observed = """
                {"status":"OBSERVED","summary":"观察到等式变形步骤","observations":[
                {"kind":"CORRECT","description":"这一步保持等式两边同减3","strokeIds":["s1"]}],
                "clarification":""}
                """;
        var location = new StrokeLocation("s1", new WorldBounds(-30, 20, 16, 12), List.of(0));
        var complete = new MultiAgentModels.Snapshot(original.fileRef(), original.questionMarkdown(),
                original.images(), List.of("s1"), true, null, null,
                StrokeLocationsStatus.COMPLETE, List.of(location));
        ReplaySteps completeSteps = emptyHistorySteps().reply(Role.DIAGNOSE, observed)
                .reply(Role.TEACH, TutoringPipelineTests.TEACH);

        var completeResult = pipeline.execute(new PipelineContext(execution, complete, List.of()), completeSteps);

        assertThat(completeResult.verified()).isTrue();
        assertThat(completeResult.bodyMarkdown()).contains("先考虑怎样消去常数项")
                .doesNotContain("不能可靠对应", "请确认");
        assertThat(completeSteps.actionCalls).isZero();
        assertThat(completeSteps.invocations).extracting(StepInvocation::role).containsExactly(
                Role.SOLVE, Role.DIAGNOSE, Role.RETRIEVE, Role.TEACH, Role.REVIEW);
        String completeDigest = completeSteps.invocations.get(0).inputDigest();

        var moved = new MultiAgentModels.Snapshot(original.fileRef(), original.questionMarkdown(),
                original.images(), List.of("s1"), true, null, null,
                StrokeLocationsStatus.COMPLETE, List.of(new StrokeLocation("s1",
                        new WorldBounds(-20, 20, 16, 12), List.of(0))));
        ReplaySteps movedSteps = emptyHistorySteps().reply(Role.DIAGNOSE, observed)
                .reply(Role.TEACH, TutoringPipelineTests.TEACH);
        assertThat(pipeline.execute(new PipelineContext(execution, moved, List.of()), movedSteps).verified()).isTrue();
        assertThat(movedSteps.invocations.get(0).inputDigest()).isNotEqualTo(completeDigest);

        // 旧七参构造保持 UNAVAILABLE；不能把只有 strokeId 的快照当作可定位笔迹。
        var legacy = new MultiAgentModels.Snapshot(original.fileRef(), original.questionMarkdown(),
                original.images(), List.of("s1"), true, null, null);
        assertThat(legacy.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.UNAVAILABLE);
        ReplaySteps legacySteps = emptyHistorySteps().reply(Role.DIAGNOSE, observed)
                .reply(Role.TEACH, TutoringPipelineTests.TEACH);
        var legacyResult = pipeline.execute(new PipelineContext(execution, legacy, List.of()), legacySteps);
        assertThat(legacyResult.verified()).isTrue();
        assertThat(legacyResult.bodyMarkdown()).contains("请说明希望检查的那一步")
                .doesNotContain("先考虑怎样消去常数项");
        assertThat(legacySteps.invocations.get(0).inputDigest()).isNotEqualTo(completeDigest);
    }

    @Test
    void knownEmptySnapshotDoesNotBecomeUnknownHandwriting() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        var original = context(execution).snapshot();
        var snapshot = new MultiAgentModels.Snapshot(original.fileRef(), original.questionMarkdown(),
                original.images(), List.of(), false, null, null);
        ReplaySteps steps = emptyHistorySteps().reply(Role.DIAGNOSE, TutoringPipelineTests.EMPTY_DIAGNOSIS)
                .reply(Role.TEACH, TutoringPipelineTests.TEACH);
        var result = pipeline.execute(new PipelineContext(execution, snapshot, List.of()), steps);
        assertThat(result.verified()).isTrue();
        assertThat(result.bodyMarkdown()).doesNotContain("请确认");
    }

    @Test
    void inconsistentHandwritingMetadataFailsBeforeAnyStep() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        var original = context(execution).snapshot();
        var snapshot = new MultiAgentModels.Snapshot(original.fileRef(), original.questionMarkdown(),
                original.images(), List.of("s1"), false, null, null);
        ReplaySteps steps = emptyHistorySteps();
        assertThat(pipeline.execute(new PipelineContext(execution, snapshot, List.of()), steps).verified()).isFalse();
        assertThat(steps.invocations).isEmpty();
    }

    @Test
    void legalEvidenceIdsCannotSmuggleClaimDifferentFromSourceAnchor() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        when(sources.get(42L, SOURCE, 1L)).thenReturn(sourceContent("ACTIVE", 1, "READY"));
        Evidence forged = new Evidence(EVIDENCE, RUN, "step-retrieve", "HISTORY", SOURCE, 1L, ANCHOR,
                "伪造的权威答案x=99", null);
        ReplaySteps steps = historySteps(markerTeach()).retrieval(List.of(forged));
        var result = pipeline.execute(knownEmptyContext(execution), steps);
        assertThat(result.verified()).isFalse();
        assertThat(steps.invocations).extracting(StepInvocation::role)
                .containsExactly(Role.SOLVE, Role.DIAGNOSE, Role.RETRIEVE);
        verify(ledger, never()).recordEvidence(eq(42L), eq(RUN), anyList());
    }

    private static String markerTeach() {
        return CLARIFYING_TEACH.replace("先考虑怎样消去常数项。",
                "先考虑怎样消去常数项。[[evidence:" + EVIDENCE + "]]")
                .replace("\"evidenceIds\":[]", "\"evidenceIds\":[\"" + EVIDENCE + "\"]");
    }

    private static Evidence historyEvidence() {
        return new Evidence(EVIDENCE, RUN, "step-retrieve", "HISTORY", SOURCE, 1L, ANCHOR,
                "同类题先核对等式两边的运算。", null);
    }

    private static SourceContent sourceContent(SourceType type, String answerState) {
        Instant time = Instant.parse("2026-10-25T00:00:00Z");
        return new SourceContent(new Source(SOURCE, 42L, type, "origin", "笔记", "ACTIVE", 1L, time),
                new SourceVersion(SOURCE, 1L, "hash", "READY", answerState, null,
                        historyEvidence().claim(), time),
                List.of(new Anchor(ANCHOR, SOURCE, 1L,
                        type == SourceType.SAVED_ANSWER ? "HANDWRITING_STEP" : "MARKDOWN_BLOCK", 0, "hash", "{}",
                        historyEvidence().claim())));
    }

    private static SourceContent sourceContent(String status, long currentVersion, String indexStatus) {
        Instant time = Instant.parse("2026-10-25T00:00:00Z");
        return new SourceContent(new Source(SOURCE, 42L, SourceType.NOTE, "origin", "笔记", status,
                currentVersion, time), new SourceVersion(SOURCE, 1L, "hash", indexStatus,
                null, null, "同类题先核对等式两边的运算。", time),
                List.of(new Anchor(ANCHOR, SOURCE, 1L, "MARKDOWN_BLOCK", 0, "hash", "{}",
                        "同类题先核对等式两边的运算。")));
    }

    private static Execution execution(MultiAgentModels.Status status, Instant cancel) {
        return new Execution(42L, REQUEST, "17", RUN, MultiAgentModels.Mode.HINT,
                "请给我提示，先看看写得对不对。", "fixed-snapshot", SOURCE, 1L,
                status, 1L, null, Instant.now().plusSeconds(300), cancel, 0);
    }

    private static PipelineContext context(Execution execution) {
        return new PipelineContext(execution, new MultiAgentModels.Snapshot("fixed-snapshot",
                "解方程2x+3=17", List.of(new Image(new byte[] {1, 2, 3}, "image/png",
                        "x=-40,y=0,width=800,height=300"))), List.of());
    }

    private static PipelineContext knownEmptyContext(Execution execution) {
        var original = context(execution).snapshot();
        var snapshot = new MultiAgentModels.Snapshot(original.fileRef(), original.questionMarkdown(),
                original.images(), List.of(), false, null, null);
        return new PipelineContext(execution, snapshot, List.of());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> result = mock(ObjectProvider.class);
        when(result.getIfAvailable()).thenReturn(value);
        return result;
    }

    private static StepOutput stored(Object value) {
        String json = RoleOutputValidator.JSON.writeValueAsString(value);
        return new StepOutput(json, TutoringPipeline.sha256(json));
    }

    private static ReplaySteps emptyHistorySteps() {
        return new ReplaySteps().reply(Role.SOLVE, TutoringPipelineTests.SOLVE)
                .reply(Role.DIAGNOSE, UNCERTAIN_DIAGNOSIS)
                .retrieval(List.of())
                .reply(Role.TEACH, CLARIFYING_TEACH)
                .reply(Role.REVIEW, TutoringPipelineTests.REVIEW);
    }

    private static ReplaySteps historySteps(String teach) {
        return new ReplaySteps().reply(Role.SOLVE, TutoringPipelineTests.SOLVE)
                .reply(Role.DIAGNOSE, TutoringPipelineTests.EMPTY_DIAGNOSIS)
                .retrieval(List.of(historyEvidence()))
                .reply(Role.TEACH, teach)
                .reply(Role.REVIEW, TutoringPipelineTests.REVIEW);
    }

    private static final class ReplaySteps implements StepExecutor {
        private final Map<Role, StepOutput> confirmed = new EnumMap<>(Role.class);
        private final List<StepInvocation> invocations = new ArrayList<>();
        private Role corrupt;
        private Role unknown;
        private int actionCalls;

        private ReplaySteps reply(Role role, String output) {
            return put(role, new DefaultRolePipeline.StoredReply(output, null, "stored-fixture",
                    new Usage(15, 10, 1, false)));
        }

        private ReplaySteps retrieval(List<Evidence> items) {
            return put(Role.RETRIEVE, new DefaultRolePipeline.StoredRetrieval(items));
        }

        private ReplaySteps put(Role role, Object value) {
            confirmed.put(role, stored(value));
            return this;
        }

        @Override
        public StepOutput execute(StepInvocation invocation, StepAction action) {
            invocations.add(invocation);
            if (invocation.role() == unknown) {
                throw new StepExecutionException("OUTCOME_UNKNOWN");
            }
            StepOutput output = confirmed.get(invocation.role());
            if (output == null) {
                actionCalls++;
                return action.call();
            }
            if (invocation.role() == corrupt) {
                return new StepOutput(output.output(), "incorrect-digest");
            }
            return output;
        }
    }
}
