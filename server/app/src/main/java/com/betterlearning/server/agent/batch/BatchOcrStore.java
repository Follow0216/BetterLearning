package com.betterlearning.server.agent.batch;

import com.betterlearning.server.agent.model.BatchOcrModels.Result;

/** 独立批次身份与租约；所有操作显式限定账号，图片不进入此端口。 */
public interface BatchOcrStore {
    record Snapshot(String requestId, String fingerprint, String status, String stage,
            int completedTiles, int totalTiles, Result result, String errorCode, String message) {
        public boolean active() {
            return "QUEUED".equals(status) || "RUNNING".equals(status);
        }
    }

    record Created(Snapshot snapshot, boolean created) {
    }

    enum Start {
        STARTED, WAITING, FENCED
    }

    Snapshot find(long accountId, String requestId);

    Created create(long accountId, String requestId, String fingerprint, String owner);

    Start start(long accountId, String requestId, String owner);

    boolean heartbeat(long accountId, String requestId, String owner);

    boolean progress(long accountId, String requestId, String owner, int completed, int total,
            String stage, Result partial);

    void finish(long accountId, String requestId, String owner, String status,
            Result result, String errorCode, String message);

    Snapshot cancel(long accountId, String requestId);

    void releaseCancelled(long accountId, String requestId, String owner);
}
