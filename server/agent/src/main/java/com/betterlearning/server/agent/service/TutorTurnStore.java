package com.betterlearning.server.agent.service;

import java.util.List;
import java.util.Optional;

import com.betterlearning.server.agent.model.TutorModels;

/** C1在app适配learning存储；每次操作自身提交，不跨模型调用持有数据库事务。 */
public interface TutorTurnStore {
    /** 先按账号/题目授权；账号级requestId+同题同prompt返回原轮，不同内容409；同账号跨题最多一个活动轮。 */
    TutorModels.Turn createOrGet(long accountId, String questionId, String requestId, String prompt);

    /** 最近50轮，按createdAt/turnId正序；必须先验证题目属于当前账号。 */
    List<TutorModels.Turn> list(long accountId, String questionId);

    TutorModels.Turn get(long accountId, String questionId, String turnId);

    /** 仅返回当前题WAITING_ANSWER且fileRef=null的轮次；空结果也必须先验证题目归属。 */
    Optional<TutorModels.Turn> pending(long accountId, String questionId);

    /**
     * 原子领取：已有完整fileRef的WAITING_ANSWER，或retry=true且已有fileRef的FAILED转GENERATING。
     * 已GENERATING/SUCCEEDED返回claimed=false原轮；其他非法重试409。retry也须原子满足账号级活动唯一约束。
     * 文件绑定由C1完整上传事务完成且只允许一次，不能以M2当前作答替代。
     */
    TutorModels.GenerationClaim claimGeneration(long accountId, String questionId, String turnId, boolean retry);

    /** 只读该轮不可变完整冻结包，返回全部合成PNG，顺序与manifest一致。 */
    TutorModels.FrozenAnswer frozenAnswer(long accountId, String questionId, String turnId);

    /** 仅GENERATING+相同fileRef可提交；已完成则返回原轮，不覆盖其他结果。 */
    TutorModels.Turn succeed(long accountId, String questionId, String turnId,
            String fileRef, String answerMarkdown);

    /** 仅GENERATING+相同fileRef可提交；保留fileRef供原轮重试，不存供应商原始错误。 */
    TutorModels.Turn fail(long accountId, String questionId, String turnId,
            String fileRef, String failureCode, String failureMessage);

    /** 仅WAITING_ANSWER可取消，标FAILED/TABLET_CANCELLED；已结束轮重复调用返回原轮。 */
    TutorModels.Turn cancelWaiting(long accountId, String questionId, String turnId);
}
