package com.betterlearning.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.service.impl.FileRetentionService;
import com.betterlearning.server.multiagent.MultiAgentModels;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.TutorLedger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TutorRequestServiceTests {
    private static final String REQUEST_ID = "6e4631e4-6e92-40ac-b6b0-b090ed202c9a";
    private static final String RUN_ID = "bb98484f-6fd8-41a4-bd63-745e9aa2d259";
    private static final String FILE_REF = "6a528b9b-4d8b-4639-aef2-c700a29a648d";
    private static final String SOURCE_ID = "2bf3d58a-6af9-4881-974f-0926a587cf92";
    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");

    private final TutorLedger ledger = mock(TutorLedger.class);
    private final SourceStore sources = mock(SourceStore.class);
    private final LearningDao questions = mock(LearningDao.class);
    private final AnswerFileStore files = mock(AnswerFileStore.class);
    private final FileRetentionService retention = mock(FileRetentionService.class);
    private final TutorRequestServiceImpl service = new TutorRequestServiceImpl(
            ledger, sources, questions, files, retention);

    @Test
    void omittedModeAndSnapshotNormalizeBeforeLedgerIdempotency() {
        when(ledger.getByRequestId(7, REQUEST_ID)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        when(ledger.create(eq(7L), eq("91"), eq(REQUEST_ID), any())).thenReturn(request(null));
        var response = service.create(7, "91", REQUEST_ID,
                new TutorRequestModels.CreateRequest(REQUEST_ID, "下一步？", null, null));
        ArgumentCaptor<MultiAgentModels.CreateRequest> command =
                ArgumentCaptor.forClass(MultiAgentModels.CreateRequest.class);
        verify(ledger).create(eq(7L), eq("91"), eq(REQUEST_ID), command.capture());
        assertThat(command.getValue().mode()).isEqualTo(MultiAgentModels.Mode.HINT);
        assertThat(command.getValue().snapshotInput())
                .isEqualTo(new MultiAgentModels.SnapshotInput("NEW_CAPTURE", null));
        assertThat(response.currentRunId()).isNull();
        assertThat(response.status()).isEqualTo("WAITING_INPUT");
    }

    @Test
    void wrongKeyNeverTouchesLedger() {
        assertThatThrownBy(() -> service.create(7, "91", RUN_ID,
                new TutorRequestModels.CreateRequest(REQUEST_ID, "下一步？", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PARAM);
        verify(ledger, never()).create(anyLong(), any(), any(), any());
    }

    @Test
    void detailOmitsPrivateAttemptOutputAndPresentsCurrentRun() {
        MultiAgentModels.Attempt attempt = new MultiAgentModels.Attempt(RUN_ID, RUN_ID, 1,
                "FAILED", "UNKNOWN", 3, NOW, NOW, "private provider text", "private digest");
        MultiAgentModels.Run run = new MultiAgentModels.Run(RUN_ID, REQUEST_ID, 1, null,
                MultiAgentModels.Status.FAILED, 2, "OUTCOME_UNKNOWN", null, List.of(), List.of(attempt));
        when(ledger.detail(7, "91", REQUEST_ID)).thenReturn(
                new MultiAgentModels.RequestDetail(request(RUN_ID), run, List.of(run), null));
        TutorRequestModels.Detail detail = service.detail(7, "91", REQUEST_ID);
        assertThat(detail.run().runId()).isEqualTo(RUN_ID);
        assertThat(detail.runs()).hasSize(1);
        assertThat(detail.run().attempts().get(0).providerCallState()).isEqualTo("UNKNOWN");
        assertThat(detail.run().attempts().get(0).toString()).doesNotContain("private provider text");
    }

    @Test
    void snapshotUsesCanvasOriginForEveryWorldPositionAndPreservesAllBlockBytes() {
        MultiAgentModels.Execution execution = new MultiAgentModels.Execution(7, REQUEST_ID, "91", RUN_ID,
                MultiAgentModels.Mode.HINT, "下一步？", FILE_REF, SOURCE_ID, 2,
                MultiAgentModels.Status.QUEUED, 1, NOW.plusSeconds(120), NOW.plusSeconds(600), null, 0);
        MultiAgentModels.Source source = new MultiAgentModels.Source(SOURCE_ID, 7,
                MultiAgentModels.SourceType.TUTOR_SNAPSHOT, REQUEST_ID, "冻结", "ACTIVE", 2, NOW);
        MultiAgentModels.SourceVersion version = new MultiAgentModels.SourceVersion(SOURCE_ID, 2,
                "0".repeat(64), "READY", "DRAFT", FILE_REF, null, NOW);
        List<AnswerModels.Block> blocks = List.of(
                block(0, "chunk_0000", 0, 0, 1024, 1024, 1024, 1024),
                block(1, "chunk_0001", 1024, 0, 276, 1024, 276, 1024),
                block(2, "chunk_0002", 0, 1024, 1024, 76, 1024, 76),
                block(3, "chunk_0003", 1024, 1024, 276, 76, 276, 76));
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout("0".repeat(64), 100.0, 16.0,
                "renderer-v1", new AnswerModels.Bounds(0.0, 0.0, 100.0, 100.0), List.of(), List.of());
        List<AnswerModels.Stroke> strokes = List.of(
                new AnswerModels.Stroke("outside-1", "#152744", 2.0,
                List.of(new AnswerModels.Point(-10.0, -8.0, null))),
                new AnswerModels.Stroke("below-2", "#152744", 2.0,
                List.of(new AnswerModels.Point(100.0, 950.0, 0.5))));
        AnswerModels.SaveManifest manifest = new AnswerModels.SaveManifest(1, REQUEST_ID, null,
                new AnswerModels.Canvas(1300.0, 1100.0, -60.0, -90.0, "logical"),
                new AnswerModels.Document(1, layout, strokes), blocks);
        byte[][] original = {
                {(byte) 0x89, 80, 78, 71, 0},
                {(byte) 0x89, 80, 78, 71, 1, 2},
                {(byte) 0x89, 80, 78, 71, 3, 4, 5},
                {(byte) 0x89, 80, 78, 71, 6, 7, 8, 9}
        };
        when(ledger.getByRequestId(7, REQUEST_ID)).thenReturn(request(RUN_ID));
        when(ledger.execution(7, RUN_ID)).thenReturn(execution);
        when(sources.get(7, SOURCE_ID, 2)).thenReturn(new MultiAgentModels.SourceContent(source, version, List.of()));
        when(files.manifest(FILE_REF)).thenReturn(new AnswerModels.StoredManifest(manifest,
                new AnswerModels.QuestionSnapshot("91", "OTHER", "负坐标题目")));
        for (int index = 0; index < blocks.size(); index++) {
            when(files.block(FILE_REF, blocks.get(index).partName())).thenReturn(original[index]);
        }

        MultiAgentModels.Snapshot snapshot = service.load(7, REQUEST_ID);

        assertThat(snapshot.images()).hasSize(4);
        String[] positions = {
                "index=0, worldX=-60.0, worldY=-90.0, width=1024.0, height=1024.0, pixelWidth=1024, pixelHeight=1024",
                "index=1, worldX=964.0, worldY=-90.0, width=276.0, height=1024.0, pixelWidth=276, pixelHeight=1024",
                "index=2, worldX=-60.0, worldY=934.0, width=1024.0, height=76.0, pixelWidth=1024, pixelHeight=76",
                "index=3, worldX=964.0, worldY=934.0, width=276.0, height=76.0, pixelWidth=276, pixelHeight=76"
        };
        for (int index = 0; index < positions.length; index++) {
            assertThat(snapshot.images().get(index).position()).isEqualTo(positions[index]);
            assertThat(snapshot.images().get(index).mediaType()).isEqualTo("image/png");
            assertThat(snapshot.images().get(index).bytes()).containsExactly(original[index]);
        }
        assertThat(snapshot.strokeIds()).containsExactly("outside-1", "below-2");
        assertThat(snapshot.hasHandwriting()).isTrue();
        assertThat(snapshot.ocrText()).isNull();
        assertThat(snapshot.ocrConfidence()).isNull();
    }

    private static AnswerModels.Block block(int index, String part, double x, double y, double width,
            double height, int pixelWidth, int pixelHeight) {
        return new AnswerModels.Block(index, part, x, y, width, height, pixelWidth, pixelHeight, "0".repeat(64));
    }

    private static MultiAgentModels.Request request(String currentRunId) {
        return new MultiAgentModels.Request(REQUEST_ID, "91", "下一步？", MultiAgentModels.Mode.HINT,
                new MultiAgentModels.SnapshotInput("NEW_CAPTURE", null),
                currentRunId == null ? null : REQUEST_ID,
                currentRunId == null ? null : FILE_REF, currentRunId,
                currentRunId == null ? MultiAgentModels.Status.WAITING_INPUT : MultiAgentModels.Status.FAILED,
                1, NOW, NOW);
    }
}
