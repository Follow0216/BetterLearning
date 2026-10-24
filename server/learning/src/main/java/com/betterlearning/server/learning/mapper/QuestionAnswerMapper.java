package com.betterlearning.server.learning.mapper;

import com.betterlearning.server.learning.model.dataobject.QuestionAnswerDO;
import org.apache.ibatis.annotations.Param;

/** 题目行锁串行化当前保存与状态，所有入口先限定账号。 */
public interface QuestionAnswerMapper {
    Long lockQuestion(@Param("accountId") long accountId, @Param("questionId") long questionId);
    QuestionAnswerDO find(@Param("accountId") long accountId, @Param("questionId") long questionId);
    QuestionAnswerDO publish(@Param("answer") QuestionAnswerDO answer);
    int updateState(@Param("accountId") long accountId, @Param("questionId") long questionId,
            @Param("fileRef") String fileRef, @Param("state") String state);
}
