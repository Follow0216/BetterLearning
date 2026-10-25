package com.betterlearning.server.multiagent;

import java.util.List;
import java.util.Optional;

import com.betterlearning.server.multiagent.MultiAgentModels.*;

/** 资料正文/版本事实源；删除立即撤销所有版本读取，索引完成只能 CAS 当前版本。 */
public interface SourceStore {
    SourceContent publish(long accountId, PublishSource command);
    SourceContent get(long accountId, String sourceId, long version);
    Optional<SourceContent> findOrigin(long accountId, SourceType type, String originId);
    List<Source> list(long accountId, SourceType type, int limit);
    void addAnchors(long accountId, String sourceId, long version, List<Anchor> anchors);
    boolean markIndex(long accountId, String sourceId, long version, String status);
    void withdraw(long accountId, String sourceId);
    boolean fileReferenced(String fileRef);
}
