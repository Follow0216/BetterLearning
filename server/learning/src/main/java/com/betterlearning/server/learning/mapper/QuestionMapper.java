package com.betterlearning.server.learning.mapper;

import java.util.List;

import com.betterlearning.server.learning.model.dataobject.QuestionDO;

import org.apache.ibatis.annotations.Param;

/** 题目 SQL 映射；每个操作都显式绑定真实账号。 */
public interface QuestionMapper {

    /** 唯一键冲突不写入，返回 null；不可用异常原样传播。 */
    Long insertIfAbsent(@Param("question") QuestionDO question);

    QuestionDO findByRequestId(@Param("accountId") long accountId,
            @Param("requestId") String requestId);

    QuestionDO findById(@Param("accountId") long accountId, @Param("questionId") long questionId);

    List<QuestionDO> findPage(@Param("accountId") long accountId,
            @Param("filtered") boolean filtered, @Param("collectionId") Long collectionId,
            @Param("offset") long offset, @Param("limit") int limit);

    long count(@Param("accountId") long accountId,
            @Param("filtered") boolean filtered, @Param("collectionId") Long collectionId);
}
