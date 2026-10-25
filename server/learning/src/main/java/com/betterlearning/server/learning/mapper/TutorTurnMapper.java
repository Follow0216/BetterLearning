package com.betterlearning.server.learning.mapper;

import java.util.List;

import com.betterlearning.server.learning.model.dataobject.TutorTurnDO;

import org.apache.ibatis.annotations.Param;

/** 所有语句显式限定账号与题号，事务统一锁question行。 */
public interface TutorTurnMapper {
    TutorTurnDO find(@Param("accountId") long accountId, @Param("questionId") long questionId,
            @Param("turnId") String turnId);

    @org.apache.ibatis.annotations.Select("SELECT EXISTS(SELECT 1 FROM ma_request WHERE account_id=#{accountId} "
            + "AND status IN ('WAITING_INPUT','QUEUED','RUNNING','CANCELLING'))")
    boolean activeRequest(@Param("accountId") long accountId);

    Long lockAccount(@Param("accountId") long accountId);

    TutorTurnDO findRequest(@Param("accountId") long accountId, @Param("requestId") String requestId);

    TutorTurnDO active(@Param("accountId") long accountId);

    List<TutorTurnDO> recent(@Param("accountId") long accountId, @Param("questionId") long questionId);

    TutorTurnDO insert(@Param("turn") TutorTurnDO turn);

    int bind(@Param("accountId") long accountId, @Param("questionId") long questionId,
            @Param("turnId") String turnId, @Param("fileRef") String fileRef, @Param("hash") String hash);

    int claim(@Param("accountId") long accountId, @Param("questionId") long questionId,
            @Param("turnId") String turnId, @Param("status") String status);

    int finish(@Param("accountId") long accountId, @Param("questionId") long questionId,
            @Param("turnId") String turnId, @Param("fileRef") String fileRef, @Param("status") String status,
            @Param("answer") String answer, @Param("failureCode") String failureCode,
            @Param("failureMessage") String failureMessage);

    int cancel(@Param("accountId") long accountId, @Param("questionId") long questionId,
            @Param("turnId") String turnId);
}
