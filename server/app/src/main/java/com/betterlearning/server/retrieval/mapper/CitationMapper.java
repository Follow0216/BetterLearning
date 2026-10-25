package com.betterlearning.server.retrieval.mapper;

import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 仅返回实时授权通过的已发布引用；内容校验由 CitationService 完成。 */
@Mapper
public interface CitationMapper {
    Long lockAccount(@Param("accountId") long accountId);

    Map<String, Object> authorized(@Param("accountId") long accountId,
            @Param("citationId") String citationId);
}
