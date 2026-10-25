package com.betterlearning.server.agent.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import com.betterlearning.server.agent.config.AgentProperties;
import com.betterlearning.server.multiagent.EvidenceRetriever;
import com.betterlearning.server.multiagent.MultiAgentModels;
import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.Image;
import com.betterlearning.server.multiagent.MultiAgentModels.PipelineContext;
import com.betterlearning.server.multiagent.MultiAgentModels.Snapshot;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocationsStatus;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.StepExecutor;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.multiagent.TutorLedger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 位置容量受限时保留原图与笔划ID，先核验运行态，再给固定可操作说明。 */
class LocationBudgetEarlyExitTests {
    private static final long ACCOUNT = 42L;
    private static final String RUN = "location-budget-run";
    private static final String REQUEST = "location-budget-request";
    private static final String FILE_REF = "location-budget-snapshot";
    private static final String CAPACITY_CLARIFICATION = "本次作答的完整笔划定位数据超过当前处理范围，暂时无法逐笔判断对错。"
            + "请保留原作答；重试同一份内容或仅提升图片清晰度不能解除容量限制。"
            + "若需继续精确检查，请将目标步骤另行写成较小范围的独立作答后重新发起辅导。";

    @Test
    void twoThousandOneStrokeIdsExitWithFixedGuidanceAndOnlyActiveRead() {
        Fixture fixture = new Fixture();
        when(fixture.ledger.execution(ACCOUNT, RUN)).thenReturn(running());
        List<String> ids = IntStream.range(0, 2001).mapToObj(index -> "stroke-" + index).toList();

        var result = fixture.pipeline.execute(context(snapshot(ids, true, null, smallImages())), fixture.steps);

        assertThat(result.verified()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.bodyMarkdown()).isEqualTo(CAPACITY_CLARIFICATION);
        assertThat(result.evidenceIds()).isEmpty();
        fixture.assertOnlyActiveRead();
    }

    @Test
    void manifestByteLimitFlagWithOneStrokeAndLowOcrUsesSameGuidance() {
        for (Double confidence : new Double[] {null, 0.2}) {
            Fixture fixture = new Fixture();
            when(fixture.ledger.execution(ACCOUNT, RUN)).thenReturn(running());

            var result = fixture.pipeline.execute(context(snapshot(List.of("s1"), true,
                    confidence, smallImages())), fixture.steps);

            assertThat(result.verified()).isTrue();
            assertThat(result.bodyMarkdown()).isEqualTo(CAPACITY_CLARIFICATION);
            assertThat(result.evidenceIds()).isEmpty();
            fixture.assertOnlyActiveRead();
        }
    }

    @Test
    void cancelledExpiredNonRunningMismatchedAndMissingRunCannotPublishGuidance() {
        Instant future = Instant.now().plusSeconds(300);
        for (Execution current : new Execution[] {
                execution(MultiAgentModels.Status.RUNNING, REQUEST, future, Instant.now()),
                execution(MultiAgentModels.Status.CANCELLED, REQUEST, future, null),
                execution(MultiAgentModels.Status.RUNNING, REQUEST, Instant.now().minusSeconds(1), null),
                execution(MultiAgentModels.Status.QUEUED, REQUEST, future, null),
                execution(MultiAgentModels.Status.RUNNING, "another-request", future, null),
                null}) {
            Fixture fixture = new Fixture();
            when(fixture.ledger.execution(ACCOUNT, RUN)).thenReturn(current);

            var result = fixture.pipeline.execute(context(snapshot(List.of("s1"), true,
                    null, smallImages())), fixture.steps);

            assertThat(result.verified()).isFalse();
            assertThat(result.bodyMarkdown()).isNull();
            assertThat(result.evidenceIds()).isEmpty();
            fixture.assertOnlyActiveRead();
        }
    }

    @Test
    void unknownMetadataOrEmptyStrokeIdsKeepsOriginalExecutor() {
        assertFallsThroughToOriginalExecutor(
                snapshot(List.of("s1"), null, null, smallImages()), running(), running());
        assertFallsThroughToOriginalExecutor(
                snapshot(List.of(), false, null, smallImages()), running(), running());
    }

    @Test
    void priorOrCurrentModelCallsKeepOriginalExecutor() {
        Snapshot limited = snapshot(List.of("s1"), true, null, smallImages());
        assertFallsThroughToOriginalExecutor(limited, runningWithCalls(1), running());
        assertFallsThroughToOriginalExecutor(limited, running(), runningWithCalls(1));
    }

    private static void assertFallsThroughToOriginalExecutor(Snapshot snapshot,
            Execution original, Execution current) {
        Fixture fixture = new Fixture();
        when(fixture.ledger.execution(ACCOUNT, RUN)).thenReturn(current);
        when(fixture.steps.execute(any(), any())).thenThrow(new StepExecutionException("OUTCOME_UNKNOWN"));

        var result = fixture.pipeline.execute(new PipelineContext(original, snapshot, List.of()), fixture.steps);

        assertThat(result.verified()).isFalse();
        assertThat(result.reason()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(result.bodyMarkdown()).isNull();
        verify(fixture.steps).execute(any(), any());
        verify(fixture.ledger, atLeastOnce()).execution(ACCOUNT, RUN);
        verifyNoMoreInteractions(fixture.ledger, fixture.steps);
        verifyNoInteractions(fixture.sources, fixture.retriever);
    }

    @Test
    void imageBudgetStillWinsOverLocationFallbackBeforeLedgerRead() {
        Fixture fixture = new Fixture();
        Image image = new Image(new byte[] {1}, "image/png", "x=-3,y=0,width=1,height=1");
        Snapshot tooManyImages = snapshot(List.of("s1"), true, null,
                Collections.nCopies(257, image));

        var result = fixture.pipeline.execute(context(tooManyImages), fixture.steps);

        assertThat(result.verified()).isFalse();
        assertThat(result.reason()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(result.bodyMarkdown()).isNull();
        verifyNoInteractions(fixture.ledger, fixture.steps, fixture.sources, fixture.retriever);
    }

    @Test
    void contradictoryHandwritingMetadataCannotTakeSuccessfulEarlyExit() {
        Fixture fixture = new Fixture();
        Snapshot invalid = snapshot(List.of("s1"), false, null, smallImages());

        var result = fixture.pipeline.execute(context(invalid), fixture.steps);

        assertThat(result.verified()).isFalse();
        assertThat(result.bodyMarkdown()).isNull();
        verifyNoInteractions(fixture.ledger, fixture.steps, fixture.sources, fixture.retriever);
    }

    private static List<Image> smallImages() {
        return List.of(new Image(new byte[] {1, 2}, "image/png", "x=-3,y=0,width=1,height=1"));
    }

    private static Snapshot snapshot(List<String> strokeIds, Boolean handwriting, Double ocrConfidence,
            List<Image> images) {
        return new Snapshot(FILE_REF, "解方程 2x+3=17。", images, strokeIds,
                handwriting, null, ocrConfidence, StrokeLocationsStatus.TOO_LARGE, List.of());
    }

    private static PipelineContext context(Snapshot snapshot) {
        return new PipelineContext(running(), snapshot, List.of());
    }

    private static Execution running() {
        return runningWithCalls(0);
    }

    private static Execution runningWithCalls(int modelCalls) {
        return execution(MultiAgentModels.Status.RUNNING, REQUEST,
                Instant.now().plusSeconds(300), null, modelCalls);
    }

    private static Execution execution(MultiAgentModels.Status status, String requestId,
            Instant deadline, Instant cancellation) {
        return execution(status, requestId, deadline, cancellation, 0);
    }

    private static Execution execution(MultiAgentModels.Status status, String requestId,
            Instant deadline, Instant cancellation, int modelCalls) {
        return new Execution(ACCOUNT, requestId, "question-location", RUN, MultiAgentModels.Mode.HINT,
                "请给下一步提示", FILE_REF, "source-location", 1L, status, 1L,
                null, deadline, cancellation, modelCalls);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> result = mock(ObjectProvider.class);
        when(result.getIfAvailable()).thenReturn(value);
        return result;
    }

    private static final class Fixture {
        private final TutorLedger ledger = mock(TutorLedger.class);
        private final SourceStore sources = mock(SourceStore.class);
        private final EvidenceRetriever retriever = mock(EvidenceRetriever.class);
        private final StepExecutor steps = mock(StepExecutor.class);
        private final DefaultRolePipeline pipeline = new DefaultRolePipeline(new AgentProperties(),
                provider(ledger), provider(sources), provider(retriever));

        private void assertOnlyActiveRead() {
            verify(ledger).execution(ACCOUNT, RUN);
            verifyNoMoreInteractions(ledger);
            verifyNoInteractions(steps, sources, retriever);
        }
    }
}
