package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.betterlearning.server.learning.mapper.NoteMapper;
import com.betterlearning.server.learning.mapper.NoteMapper.NoteRow;
import com.betterlearning.server.learning.mapper.NoteMapper.SourceRow;
import com.betterlearning.server.learning.model.note.NoteModels;
import com.betterlearning.server.learning.service.NoteService.NoteFailure;
import com.betterlearning.server.learning.service.impl.NoteServiceImpl;
import com.betterlearning.server.learning.validation.NoteContent;
import com.betterlearning.server.multiagent.MultiAgentModels.Source;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceVersion;
import com.betterlearning.server.multiagent.SourceStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoteServiceImplTests {
    private static final long ACCOUNT = 7;
    private final NoteMapper mapper = mock(NoteMapper.class);
    private final SourceStore sources = mock(SourceStore.class);
    private NoteServiceImpl service;

    @BeforeEach
    void setUp() {
        String secret = Base64.getEncoder().encodeToString(
                "a-secret-with-at-least-thirty-two-bytes".getBytes(StandardCharsets.UTF_8));
        service = new NoteServiceImpl(mapper, sources, secret);
        when(mapper.lockAccount(ACCOUNT)).thenReturn(ACCOUNT);
    }

    @Test
    void createPublishesOnceAndReplaysSameKeyButRejectsDifferentTitle() {
        String key = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        NoteModels.Write write = new NoteModels.Write("标题", "a\r\nb");
        NoteContent content = NoteContent.validate(write.title(), write.bodyMarkdown());
        Instant now = Instant.parse("2026-10-25T00:00:00Z");
        when(sources.publish(eq(ACCOUNT), any())).thenAnswer(invocation -> {
            var command = (com.betterlearning.server.multiagent.MultiAgentModels.PublishSource) invocation.getArgument(1);
            return new SourceContent(new Source(sourceId, ACCOUNT, SourceType.NOTE, command.originId(),
                    command.title(), "ACTIVE", 1, now), new SourceVersion(sourceId, 1,
                    command.contentSha256(), "INDEX_PENDING", null, null, command.bodyMarkdown(), now), List.of());
        });
        when(mapper.note(eq(ACCOUNT), any())).thenAnswer(invocation -> new NoteRow(
                invocation.getArgument(1), sourceId, 1, "标题", "a\nb", "INDEX_PENDING", now, "ACTIVE"));
        when(mapper.request(ACCOUNT, key)).thenReturn(null,
                new NoteMapper.RequestRow("note-original", content.requestSha256()),
                new NoteMapper.RequestRow("note-original", content.requestSha256()));

        NoteModels.Note created = service.create(ACCOUNT, key, write);
        NoteModels.Note replayed = service.create(ACCOUNT, key, write);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.bodyMarkdown()).isEqualTo("a\nb");
        assertThat(replayed.noteId()).isEqualTo("note-original");
        assertThatThrownBy(() -> service.create(ACCOUNT, key, new NoteModels.Write("改名", "a\nb")))
                .isInstanceOf(NoteFailure.class);
        verify(sources, times(1)).publish(eq(ACCOUNT), any());
        verify(mapper).addRequest(eq(ACCOUNT), eq(key), eq(created.noteId()), eq(sourceId),
                eq(content.requestSha256()));
    }

    @Test
    void updateRequiresCurrentStrongTagAndDeleteIsIdempotent() {
        String noteId = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-10-25T00:00:00Z");
        NoteRow active = new NoteRow(noteId, sourceId, 2, "原", "正文", "READY", now, "ACTIVE");
        when(mapper.note(ACCOUNT, noteId)).thenReturn(active);

        assertThatThrownBy(() -> service.update(ACCOUNT, noteId, "W/" + NoteContent.etag(noteId, 2),
                new NoteModels.Write("新", "正文"))).isInstanceOf(NoteFailure.class);
        assertThatThrownBy(() -> service.update(ACCOUNT, noteId, null,
                new NoteModels.Write("新", "正文"))).isInstanceOf(NoteFailure.class);
        assertThatThrownBy(() -> service.delete(ACCOUNT, noteId, "*"))
                .isInstanceOf(NoteFailure.class);
        service.delete(ACCOUNT, noteId, NoteContent.etag(noteId, 2));
        verify(sources).withdraw(ACCOUNT, sourceId);
        when(mapper.note(ACCOUNT, noteId)).thenReturn(
                new NoteRow(noteId, sourceId, 2, "原", "正文", "READY", now, "DELETED"));
        service.delete(ACCOUNT, noteId, NoteContent.etag(noteId, 2));
        verify(sources, times(1)).withdraw(ACCOUNT, sourceId);
    }

    @Test
    void sourceCursorIsSignedAndBoundToAccountAndFilter() {
        Instant now = Instant.parse("2026-10-25T00:00:00Z");
        SourceRow first = new SourceRow(UUID.randomUUID().toString(), "NOTE", UUID.randomUUID().toString(),
                "ACTIVE", now, 1, "INDEX_PENDING", null);
        SourceRow second = new SourceRow(UUID.randomUUID().toString(), "NOTE", UUID.randomUUID().toString(),
                "ACTIVE", now.minusSeconds(1), 1, "READY", null);
        when(mapper.sources(ACCOUNT, "NOTE", null, null, 2)).thenReturn(List.of(first, second));
        String cursor = service.sources(ACCOUNT, "NOTE", null, 1).nextCursor();
        assertThat(cursor).isNotBlank();
        assertThatThrownBy(() -> service.sources(ACCOUNT + 1, "NOTE", cursor, 1))
                .isInstanceOf(NoteFailure.class);
        assertThatThrownBy(() -> service.sources(ACCOUNT, "QUESTION", cursor, 1))
                .isInstanceOf(NoteFailure.class);
        assertThatThrownBy(() -> service.sources(ACCOUNT, "NOTE", cursor + "x", 1))
                .isInstanceOf(NoteFailure.class);
    }
}
