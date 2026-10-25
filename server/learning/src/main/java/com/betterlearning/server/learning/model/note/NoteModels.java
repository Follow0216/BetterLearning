package com.betterlearning.server.learning.model.note;

import java.time.Instant;
import java.util.List;

/** 独立笔记 HTTP 载荷；账号只来自认证上下文。 */
public final class NoteModels {
    private NoteModels() {
    }

    public record Write(String title, String bodyMarkdown) {
    }

    public record Note(String noteId, String sourceId, long version, String title, String bodyMarkdown,
            String indexStatus, Instant updatedAt) {
    }

    public record Page(List<Note> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
