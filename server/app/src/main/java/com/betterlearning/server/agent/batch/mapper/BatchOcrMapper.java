package com.betterlearning.server.agent.batch.mapper;

import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 仅映射独立批量识别表；不依赖题库或多 Agent 协议。 */
@Mapper
public interface BatchOcrMapper {
    Long lockAdmission();

    Long lockAccount(@Param("accountId") long accountId);

    int expire(@Param("accountId") long accountId, @Param("requestId") String requestId);

    Map<String, Object> find(@Param("accountId") long accountId, @Param("requestId") String requestId);

    int activeCount(@Param("accountId") Long accountId, @Param("runningOnly") boolean runningOnly);

    int insert(@Param("accountId") long accountId, @Param("requestId") String requestId,
            @Param("fingerprint") String fingerprint, @Param("owner") String owner);

    int start(@Param("accountId") long accountId, @Param("requestId") String requestId,
            @Param("owner") String owner);

    int heartbeat(@Param("accountId") long accountId, @Param("requestId") String requestId,
            @Param("owner") String owner);

    int progress(@Param("accountId") long accountId, @Param("requestId") String requestId,
            @Param("owner") String owner, @Param("completed") int completed, @Param("total") int total,
            @Param("stage") String stage, @Param("resultJson") String resultJson);

    int finish(@Param("accountId") long accountId, @Param("requestId") String requestId,
            @Param("owner") String owner, @Param("status") String status,
            @Param("resultJson") String resultJson, @Param("errorCode") String errorCode,
            @Param("message") String message);

    int cancel(@Param("accountId") long accountId, @Param("requestId") String requestId);

    int releaseCancelled(@Param("accountId") long accountId, @Param("requestId") String requestId,
            @Param("owner") String owner);
}
