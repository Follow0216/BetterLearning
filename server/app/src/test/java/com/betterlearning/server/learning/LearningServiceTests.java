package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.mapper.SourceMapper;
import com.betterlearning.server.learning.model.dataobject.QuestionDO;
import com.betterlearning.server.learning.model.dto.ConfirmQuestionDTO;
import com.betterlearning.server.learning.service.impl.LearningServiceImpl;
import com.betterlearning.server.multiagent.SourceStore;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LearningServiceTests {

    private static final String REQUEST_ID = "74615d72-665b-4f56-a9a1-3215809de3ea";

    private final LearningDao dao = mock(LearningDao.class);
    private final SourceMapper sourceMapper = mock(SourceMapper.class);
    private final SourceStore sourceStore = mock(SourceStore.class);
    private final LearningServiceImpl service = new LearningServiceImpl(dao, sourceMapper, sourceStore);

    {
        when(sourceMapper.lockAccount(7L)).thenReturn(7L);
    }

    @Test
    void retryReturnsOriginalRecordAndUsesAuthenticatedOwner() {
        QuestionDO row = row();
        when(dao.insertIfAbsent(any(QuestionDO.class))).thenReturn(null);
        when(dao.findByRequestId(7L, REQUEST_ID)).thenReturn(Optional.of(row));
        assertThat(service.confirm(7L, new ConfirmQuestionDTO(REQUEST_ID, "OTHER", "body"))
                .questionId()).isEqualTo("91");
        ArgumentCaptor<QuestionDO> inserted = ArgumentCaptor.forClass(QuestionDO.class);
        verify(dao).insertIfAbsent(inserted.capture());
        assertThat(inserted.getValue().getAccountId()).isEqualTo(7L);
        assertThat(inserted.getValue().getCollectionId()).isNull();
    }

    @Test
    void reusedRequestWithDifferentContentIsConflict() {
        when(dao.insertIfAbsent(any(QuestionDO.class))).thenReturn(null);
        when(dao.findByRequestId(7L, REQUEST_ID)).thenReturn(Optional.of(row()));
        assertThatThrownBy(() -> service.confirm(7L,
                new ConfirmQuestionDTO(REQUEST_ID, "OTHER", "different")))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.CONFIRM_REQUEST_CONFLICT));
    }

    @Test
    void reusedRequestWithDifferentSubjectIsConflict() {
        when(dao.insertIfAbsent(any(QuestionDO.class))).thenReturn(null);
        when(dao.findByRequestId(7L, REQUEST_ID)).thenReturn(Optional.of(row()));
        assertThatThrownBy(() -> service.confirm(7L,
                new ConfirmQuestionDTO(REQUEST_ID, "HIGHER_MATHEMATICS", "body")))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.CONFIRM_REQUEST_CONFLICT));
    }

    @Test
    void rejectsOtherOwnersCollectionAndInvalidPaginationBeforeReadingPage() {
        assertThatThrownBy(() -> service.page(7L, "88", 1, 20))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.page(7L, null, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAM));
        verify(dao, never()).findPage(7L, true, 88L, 0L, 20);
    }

    @Test
    void inboxPageUsesNullCollectionAndStableOffset() {
        when(dao.findPage(7L, true, null, 20L, 20)).thenReturn(List.of(row()));
        when(dao.count(7L, true, null)).thenReturn(21L);
        var result = service.page(7L, "inbox", 2, 20);
        assertThat(result.total()).isEqualTo(21);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).collectionId()).isEqualTo("inbox");
    }

    private static QuestionDO row() {
        QuestionDO row = new QuestionDO();
        row.setQuestionId(91L);
        row.setAccountId(7L);
        row.setRequestId(REQUEST_ID);
        row.setSubject("OTHER");
        row.setBodyMarkdown("body");
        row.setSummary("body");
        row.setCreatedAt(OffsetDateTime.parse("2026-10-24T10:00:00Z"));
        return row;
    }
}
