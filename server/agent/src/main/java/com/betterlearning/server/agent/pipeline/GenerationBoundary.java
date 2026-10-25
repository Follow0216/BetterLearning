package com.betterlearning.server.agent.pipeline;

import java.util.List;
import java.util.Set;

import com.betterlearning.server.agent.pipeline.RoleProtocol.Call;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reply;

import tools.jackson.databind.JsonNode;

/**
 * P2/P4装配边界：实现必须在generate前持久化CALL_INTENT、预算与fence，确认结果后才返回。
 * 同run/sequence重放复用已保存输出；UNKNOWN必须抛出OUTCOME_UNKNOWN，不能再发起调用。
 * 此接口仅为agent内部回调，不替代common端口；pipeline本身不访问数据库、不发布结果。
 */
public interface GenerationBoundary {
    Reply generate(Call call);

    /** 每个调用前后及最终返回前检查取消/期限；抛出安全异常，禁止迟到候选继续运行。 */
    void checkActive();

    /** P4受控检索；必须在返回前按账号/ACTIVE/当前READY版本过滤。
     * 每个run至多一次，最多5条Evidence的JSON投影，不返回公开citationId/URL。 */
    List<JsonNode> retrieve(List<String> methodTags);

    /** 在最终候选形成前实时重新核验账号、source/version/anchor与撤权状态。 */
    boolean authorizeEvidence(Set<String> evidenceIds);
}
