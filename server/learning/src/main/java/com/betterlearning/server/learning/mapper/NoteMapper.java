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
