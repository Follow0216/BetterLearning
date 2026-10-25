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
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.multiagent.MultiAgentModels;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.TutorLedger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TutorRequestServiceTests {
    private static final String REQUEST_ID = "6e4631e4-6e92-40ac-b6b0-b090ed202c9a";
    private static final String RUN_ID = "bb98484f-6fd8-41a4-bd63-745e9aa2d259";
    private static final String FILE_REF = "6a528b9b-4d8b-4639-aef2-c700a29a648d";
    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");

    private final TutorLedger ledger = mock(TutorLedger.class);
    private final SourceStore sources = mock(SourceStore.class);
    private final LearningDao questions = mock(LearningDao.class);
    private final AnswerFileStore files = mock(AnswerFileStore.class);
    private final TutorRequestServiceImpl service = new TutorRequestServiceImpl(ledger, sources, questions, files);

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

    private static MultiAgentModels.Request request(String currentRunId) {
        return new MultiAgentModels.Request(REQUEST_ID, "91", "下一步？", MultiAgentModels.Mode.HINT,
                new MultiAgentModels.SnapshotInput("NEW_CAPTURE", null),
                currentRunId == null ? null : REQUEST_ID,
                currentRunId == null ? null : FILE_REF, currentRunId,
                currentRunId == null ? MultiAgentModels.Status.WAITING_INPUT : MultiAgentModels.Status.FAILED,
                1, NOW, NOW);
    }
}
