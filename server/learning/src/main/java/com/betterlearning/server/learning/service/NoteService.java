package com.betterlearning.server.learning.service;

import java.time.Instant;
import java.util.List;

import com.betterlearning.server.learning.model.note.NoteModels;

/** 笔记与来源查询边界；所有操作只接收已认证账号。 */
public interface NoteService {
    record SourceView(String sourceId, String type, String originId, String status,
            Instant createdAt, long currentVersion, String indexStatus, String answerState) {
    }

    record SourcePage(List<SourceView> items, String nextCursor) {
        public SourcePage {
            items = List.copyOf(items);
        }
    }

    enum Failure { INVALID, CONFLICT, PRECONDITION }

    final class NoteFailure extends RuntimeException {
        private final Failure failure;

        public NoteFailure(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    NoteModels.Note create(long accountId, String idempotencyKey, NoteModels.Write write);
    NoteModels.Note get(long accountId, String noteId);
    NoteModels.Note update(long accountId, String noteId, String ifMatch, NoteModels.Write write);
    void delete(long accountId, String noteId, String ifMatch);
    NoteModels.Page list(long accountId, String cursor, Integer limit);
    SourceView source(long accountId, String sourceId);
    SourcePage sources(long accountId, String type, String cursor, Integer limit);
}
