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

import com.betterlearning.server.agent.config.AgentProperties;
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
import com.betterlearning.server.multiagent.MultiAgentModels.StepInvocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StepOutput;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.StepExecutor;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.multiagent.TutorLedger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
    void confirmedReplayNeedsNoProviderConfigurationOrActionsAndRetainsClarification() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        ReplaySteps steps = emptyHistorySteps();

        var first = pipeline.execute(context(execution), steps);
        var replay = pipeline.execute(context(execution), steps);

        assertThat(first.verified()).isTrue();
        assertThat(replay).isEqualTo(first);
        assertThat(first.bodyMarkdown()).contains("请确认你希望检查的笔迹步骤")
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

        var result = pipeline.execute(context(execution), steps);

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

        var result = pipeline.execute(context(execution), steps);

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
    void evidenceIdWithoutBodyMarkerIsWithheldEvenWhenReviewSaysApproved() {
        Execution execution = execution(MultiAgentModels.Status.RUNNING, null);
        when(ledger.execution(42L, RUN)).thenReturn(execution);
        when(sources.get(42L, SOURCE, 1L)).thenReturn(sourceContent("ACTIVE", 1, "READY"));
        String noMarker = CLARIFYING_TEACH.replace("\"evidenceIds\":[]",
                "\"evidenceIds\":[\"" + EVIDENCE + "\"]");
        ReplaySteps steps = historySteps(noMarker);

        var result = pipeline.execute(context(execution), steps);

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

        var result = pipeline.execute(context(execution), steps);

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
        var result = pipeline.execute(context(execution), steps);
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
                .reply(Role.DIAGNOSE, UNCERTAIN_DIAGNOSIS)
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
