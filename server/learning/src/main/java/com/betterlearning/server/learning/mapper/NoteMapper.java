package com.betterlearning.server.learning.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/** 笔记幂等、标题版本与账号限定的键集分页。 */
public interface NoteMapper {
    record RequestRow(String noteId, String requestSha256) {
    }

    record NoteRow(String noteId, String sourceId, long version, String title,
            String bodyMarkdown, String indexStatus, Instant updatedAt, String sourceStatus) {
    }

    record SourceRow(String sourceId, String type, String originId, String status,
            Instant createdAt, long currentVersion, String indexStatus, String answerState) {
    }

    record ContextRow(String notebookId, String questionId, long revision, String resultId,
            String turnId, String requestId, String sourceId, Long sourceVersion, String versionHash,
            String excerptText, boolean available) {
    }

    record ExcerptRow(String requestId, String sourceId, Long sourceVersion, String bodyMarkdown) {
    }

    ContextRow context(@Param("accountId") long accountId, @Param("noteId") String noteId);
    int addContext(@Param("accountId") long accountId, @Param("noteId") String noteId,
            @Param("notebookId") String notebookId, @Param("questionId") String questionId,
            @Param("excerpt") com.betterlearning.server.learning.model.note.NoteModels.ExcerptWrite excerpt,
            @Param("source") ExcerptRow source, @Param("hash") String hash);
    boolean ownedQuestion(@Param("accountId") long accountId, @Param("questionId") String questionId);
    ExcerptRow resultExcerpt(@Param("accountId") long accountId, @Param("questionId") String questionId,
            @Param("resultId") String resultId);
    ExcerptRow turnExcerpt(@Param("accountId") long accountId, @Param("questionId") String questionId,
            @Param("turnId") String turnId);
    List<com.betterlearning.server.learning.model.note.NoteModels.Notebook> notebooks(@Param("accountId") long accountId);
    com.betterlearning.server.learning.model.note.NoteModels.Notebook notebook(
            @Param("accountId") long accountId, @Param("notebookId") String notebookId);
    int addNotebook(@Param("accountId") long accountId, @Param("notebookId") String notebookId,
            @Param("name") String name);
    int renameNotebook(@Param("accountId") long accountId, @Param("notebookId") String notebookId,
            @Param("name") String name);
    int unfileNotebook(@Param("accountId") long accountId, @Param("notebookId") String notebookId);
    int removeNotebook(@Param("accountId") long accountId, @Param("notebookId") String notebookId);
    int fileNote(@Param("accountId") long accountId, @Param("noteId") String noteId,
            @Param("notebookId") String notebookId);
    List<NoteRow> search(@Param("accountId") long accountId, @Param("query") String query,
            @Param("notebookId") String notebookId, @Param("unfiled") boolean unfiled,
            @Param("questionId") String questionId, @Param("beforeTime") Instant beforeTime,
            @Param("beforeId") String beforeId, @Param("limit") int limit);

    Long lockAccount(@Param("accountId") long accountId);
    RequestRow request(@Param("accountId") long accountId, @Param("key") String key);
    int addRequest(@Param("accountId") long accountId, @Param("key") String key,
            @Param("noteId") String noteId, @Param("sourceId") String sourceId,
            @Param("digest") String digest);
    int addTitle(@Param("sourceId") String sourceId, @Param("version") long version,
            @Param("title") String title);
    NoteRow note(@Param("accountId") long accountId, @Param("noteId") String noteId);
    List<NoteRow> notes(@Param("accountId") long accountId, @Param("beforeTime") Instant beforeTime,
            @Param("beforeId") String beforeId, @Param("limit") int limit);
    SourceRow source(@Param("accountId") long accountId, @Param("sourceId") String sourceId);
    List<SourceRow> sources(@Param("accountId") long accountId, @Param("type") String type,
            @Param("beforeTime") Instant beforeTime, @Param("beforeId") String beforeId,
            @Param("limit") int limit);
}
