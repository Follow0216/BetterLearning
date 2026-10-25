package com.betterlearning.server.multiagent;

import com.betterlearning.server.multiagent.MultiAgentModels.Snapshot;

/** 从请求绑定的不可变来源加载完整图像；每次读取实时授权。 */
public interface SnapshotPort {
    Snapshot load(long accountId, String requestId);
}
