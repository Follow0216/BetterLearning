package com.betterlearning.server.learning.mapper;

import java.util.List;

import com.betterlearning.server.learning.model.dataobject.QuestionCollectionDO;

import org.apache.ibatis.annotations.Param;

/** M1 只读题集 SQL，不提供题集写操作。 */
public interface QuestionCollectionMapper {

    List<QuestionCollectionDO> findAll(@Param("accountId") long accountId);

    boolean exists(@Param("accountId") long accountId, @Param("collectionId") long collectionId);
}
