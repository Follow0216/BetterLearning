package com.betterlearning.server.learning.mapper;

import java.util.List;

import com.betterlearning.server.learning.model.dataobject.QuestionCollectionDO;

import org.apache.ibatis.annotations.Param;

/** 题集 SQL；父级创建后不可更改，所有操作显式绑定账号。 */
public interface QuestionCollectionMapper {

    List<QuestionCollectionDO> findAll(@Param("accountId") long accountId);

    QuestionCollectionDO findById(@Param("accountId") long accountId,
            @Param("collectionId") long collectionId);

    Long insertReturningId(@Param("accountId") long accountId, @Param("name") String name,
            @Param("parentCollectionId") Long parentCollectionId);

    int rename(@Param("accountId") long accountId, @Param("collectionId") long collectionId,
            @Param("name") String name);

    boolean exists(@Param("accountId") long accountId, @Param("collectionId") long collectionId);
}
