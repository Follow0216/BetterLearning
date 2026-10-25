package com.betterlearning.server.learning.mapper;

import java.util.List;
import java.time.Instant;

import com.betterlearning.server.learning.model.dataobject.SourceDO;
import com.betterlearning.server.learning.model.dataobject.SourceVersionDO;
import com.betterlearning.server.learning.model.dataobject.AnchorDO;
import com.betterlearning.server.learning.model.dataobject.SourceCursorDO;
import com.betterlearning.server.learning.model.dataobject.SourceOperationDO;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;

import org.apache.ibatis.annotations.Param;

/** 资料事实源 SQL；授权条件在查询中生效。 */
public interface SourceMapper {
    Long lockAccount(@Param("accountId") long accountId);
    int create(@Param("sourceId") String sourceId, @Param("accountId") long accountId,
            @Param("type") String type, @Param("originId") String originId, @Param("title") String title);
    SourceDO lockOrigin(@Param("accountId") long accountId, @Param("type") String type,
            @Param("originId") String originId);
    SourceDO find(@Param("accountId") long accountId, @Param("sourceId") String sourceId);
    SourceDO findOrigin(@Param("accountId") long accountId, @Param("type") String type,
            @Param("originId") String originId);
    List<SourceDO> list(@Param("accountId") long accountId, @Param("type") String type,
            @Param("limit") int limit);
    List<SourceDO> page(@Param("accountId") long accountId, @Param("type") String type,
            @Param("beforeTime") Instant beforeTime, @Param("beforeId") String beforeId,
            @Param("limit") int limit);
    SourceCursorDO cursor(@Param("cursorId") String cursorId);
    int createCursor(@Param("cursorId") String cursorId, @Param("accountId") long accountId,
            @Param("type") String type, @Param("beforeTime") Instant beforeTime,
            @Param("beforeId") String beforeId);
    SourceOperationDO operation(@Param("accountId") long accountId,
            @Param("operationId") String operationId);
    int recordOperation(@Param("accountId") long accountId, @Param("operationId") String operationId,
            @Param("fingerprint") String fingerprint, @Param("sourceId") String sourceId,
            @Param("version") long version);
    SourceVersionDO version(@Param("accountId") long accountId, @Param("sourceId") String sourceId,
            @Param("version") long version);
    List<AnchorDO> anchors(@Param("accountId") long accountId, @Param("sourceId") String sourceId,
            @Param("version") long version);
    int advance(@Param("accountId") long accountId, @Param("sourceId") String sourceId,
            @Param("oldVersion") long oldVersion, @Param("newVersion") long newVersion,
            @Param("title") String title);
    int insertVersion(@Param("sourceId") String sourceId, @Param("version") long version,
            @Param("digest") String digest, @Param("answerState") String answerState,
            @Param("fileRef") String fileRef, @Param("bodyMarkdown") String bodyMarkdown);
    int insertAnchor(@Param("anchor") Anchor anchor);
    int markIndex(@Param("accountId") long accountId, @Param("sourceId") String sourceId,
            @Param("version") long version, @Param("expectedIndexRevision") long expectedIndexRevision,
            @Param("status") String status);
    int reindex(@Param("accountId") long accountId, @Param("sourceId") String sourceId,
            @Param("version") long version, @Param("expectedIndexRevision") long expectedIndexRevision);
    int withdraw(@Param("accountId") long accountId, @Param("sourceId") String sourceId);
    int withdrawRename(@Param("accountId") long accountId, @Param("sourceId") String sourceId,
            @Param("retiredOrigin") String retiredOrigin);
    int outbox(@Param("eventId") String eventId, @Param("accountId") long accountId,
            @Param("sourceId") String sourceId, @Param("version") long version,
            @Param("kind") String kind, @Param("digest") String digest,
            @Param("indexRevision") long indexRevision);
    long fileReferences(@Param("fileRef") String fileRef);
}
