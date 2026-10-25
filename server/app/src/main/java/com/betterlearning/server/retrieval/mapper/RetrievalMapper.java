package com.betterlearning.server.retrieval.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 只声明固定参数 SQL；所有检索在同一 SQL 中先过滤主库权限。 */
public interface RetrievalMapper {
    int prepareJobs();
    Map<String, Object> claim();
    Long lockAccount(long accountId);
    String lockJob(@Param("eventId") String eventId, @Param("fence") long fence);
    int deleteChunks(@Param("accountId") long accountId, @Param("sourceId") String sourceId,
            @Param("version") Long version);
    int insertChunk(Map<String, Object> row);
    int finish(@Param("eventId") String eventId, @Param("fence") long fence);
    int failure(@Param("eventId") String eventId, @Param("fence") long fence, @Param("reason") String reason);
    int renew(@Param("eventId") String eventId, @Param("fence") long fence);
    int beginExternalCall(@Param("eventId") String eventId, @Param("fence") long fence);
    int confirmExternalCall(@Param("eventId") String eventId, @Param("fence") long fence);
    int usage(Map<String, Object> usage);
    int insertQueryIntent(Map<String, Object> query);
    Map<String, Object> queryEmbedding(@Param("stepId") String stepId);
    int confirmQueryEmbedding(Map<String, Object> query);
    int eventStatus(@Param("eventId") String eventId, @Param("accountId") long accountId,
            @Param("status") String status);
    List<Map<String, Object>> candidates(Map<String, Object> query);
    String retrievalStepId(@Param("accountId") long accountId, @Param("runId") String runId);
    int authorized(@Param("accountId") long accountId, @Param("sourceId") String sourceId,
            @Param("version") long version, @Param("anchorId") String anchorId);
}
