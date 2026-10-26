package com.betterlearning.server.multiagent;

import com.betterlearning.server.multiagent.MultiAgentModels.Snapshot;

/** 从请求绑定的不可变来源加载完整图像，或返回冻结题面的显式NONE输入；每次读取实时授权。 */
public interface SnapshotPort {
    Snapshot load(long accountId, String requestId);
}
