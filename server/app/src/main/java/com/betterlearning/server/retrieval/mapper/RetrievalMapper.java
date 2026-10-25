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
    String lockJobForUnknown(@Param("eventId") String eventId, @Param("accountId") long accountId);
    String indexProtocol(@Param("eventId") String eventId, @Param("fence") long fence);
    int beginIndexProtocol(@Param("eventId") String eventId, @Param("fence") long fence,
            @Param("protocol") String protocol);
    int unconfirmedStageCount(@Param("eventId") String eventId);
    Map<String, Object> indexStage(@Param("eventId") String eventId, @Param("phase") String phase,
            @Param("ordinal") int ordinal);
    int insertIndexStage(Map<String, Object> row);
    int confirmIndexStage(Map<String, Object> row);
    int unknownIndexStage(Map<String, Object> row);
    int unknownUnconfirmedStages(@Param("eventId") String eventId);
    int currentIndexEvent(@Param("eventId") String eventId, @Param("accountId") long accountId,
            @Param("sourceId") String sourceId, @Param("version") long version,
            @Param("indexRevision") long indexRevision);
    int invalidIndexStageBindings(@Param("eventId") String eventId, @Param("model") String model,
            @Param("dimensions") int dimensions);
    List<Map<String, Object>> confirmedEmbeddingStages(@Param("eventId") String eventId);
    int confirmedOcrStageCount(@Param("eventId") String eventId);
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
