package com.betterlearning.server.learning.model.note;

import java.time.Instant;
import java.util.List;

/** 独立笔记 HTTP 载荷；账号只来自认证上下文。 */
public final class NoteModels {
    private NoteModels() {
    }

    public record Write(String title, String bodyMarkdown, ContextWrite context) {
        public Write(String title, String bodyMarkdown) {
            this(title, bodyMarkdown, null);
        }
    }

    public record ContextWrite(String notebookId, String questionId, ExcerptWrite excerpt) {
    }

    public record ExcerptWrite(String resultId, String turnId, String text) {
    }

    public record Excerpt(String resultId, String turnId, String requestId, String sourceId,
            Long version, String versionHash, String text, boolean available) {
    }

    public record Context(String notebookId, String questionId, long revision, Excerpt excerpt) {
    }

    public record Filing(String notebookId) {
    }

    public record NotebookWrite(String name) {
    }

    public record Notebook(String notebookId, String name, long revision, Instant updatedAt) {
    }

    public record Notebooks(List<Notebook> items) {
        public Notebooks {
            items = List.copyOf(items);
        }
    }

    public record Note(String noteId, String sourceId, long version, String title, String bodyMarkdown,
            String indexStatus, Instant updatedAt, Context context) {
        public Note(String noteId, String sourceId, long version, String title, String bodyMarkdown,
                String indexStatus, Instant updatedAt) {
            this(noteId, sourceId, version, title, bodyMarkdown, indexStatus, updatedAt, null);
        }
    }

    public record Page(List<Note> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
