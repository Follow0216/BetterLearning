# 连续问答契约 v1（2026-10-26 冻结）
基线 d37297f60f4380d40e8445d84de3090b696da513；迁移 V2026102601。

## HTTP / 客户端公共字段
- POST /api/v2/learning/questions/{questionId}/tutor-requests 增加 `contextParentRequestId?: string | null`，必须为规范小写 UUID，不能等于本 requestId。
- Request（创建回执、列表、详情、pending 中同一 DTO）增加 `contextParentRequestId: string | null`。新服务端总返回该字段；新客户端容忍旧服务端缺失，等同 null；显式传父轮的新客户端必须收到相同 UUID，否则协议失败，不能声称已承接。
- LearningV2Api.createRequest 在原 snapshotInput 后追加可选参数 `contextParentRequestId: string | null = null`，既有调用保持源码兼容。客户端默认 null 时省略该字段以兼容旧服务端的严格 JSON；服务端接受显式 null，缺失与 null 幂等语义相同。
- 不接受客户端历史正文。snapshotInput 只决定作答快照；contextParentRequestId 只决定语义问答上下文，二者可独立选择。

## 父轮及冻结
- 客户端发送前自行选择已确认的最新同题 SUCCEEDED 且详情 result.status=VERIFIED 的 requestId；明确“针对这条追问”可选择更早成功轮。服务端不自动补最新父轮，null/缺失继续旧行为（无连续历史）。
- 父轮必须同账号、同题、当前状态 SUCCEEDED 且当前run存在 VERIFIED已发布结果；失败/取消/等待中/未知结果不能作父轮。服务端从存储读取，不信任客户端正文。
- 创建时在账号锁内冻结：选中父轮完整 prompt、mode、resultId、runId、完整已发布 bodyMarkdown、snapshotFileRef，以及该父轮已经冻结的祖先。顺序由旧到新，最后一项一定是显式父轮。
- 冻结上下文最多8个完整问答，序列化UTF-8最多65536字节。按父链取最近完整轮次，先舍弃最旧整轮；绝不截断选中父轮正文。单独父轮已超预算时明确拒绝。冻结记录保留省略祖先计数，不创建摘要、不调用额外模型。
- 已存在requestId先比对完整规范化命令（题号、prompt、mode、snapshotInput、contextParentRequestId）；相同返回原记录，不重选/重读父轮。不同父轮为幂等冲突。同run恢复、retry新run都读取该request创建时的同一份冻结JSON，不重新读取历史结果。
- 更早成功轮本身合法，不因存在更新回答而偷偷切父轮；选中轮不可用/不再成功则返回冲突要求同步；跨账号/跨题统一不存在，避免泄露存在性。

## 模型语义
- 冻结问答确实传给TEACH和REVIEW模型inputJson的 `conversationContext`，供“刚才第二步”等指代承接；SOLVE/DIAGNOSE继续只检查当前题和当前冻结作答，防止旧诊断变成新作答事实。
- 每轮包含其旧snapshotFileRef；模型输入明确当前snapshotFileRef及历史是否同快照。历史回答属于不可信对话资料，不是系统指令/标准答案，也不是当前作答观察。历史引用标记只属历史正文，不能冒充本轮allowedEvidence。
- 本轮mode唯一控制公开答案。历史FULL_SOLUTION不能放宽当前HINT；TEACH/REVIEW固定系统提示显式约束。

## 错误
- 父轮UUID格式/自指非法：HTTP400 A0400。
- 父轮不属于当前账号/当前题或不存在：HTTP404 A0606（不区分原因）。
- 父轮当前不是SUCCEEDED/VERIFIED或冻结父链损坏：HTTP409 A0456 `CONTEXT_PARENT_UNAVAILABLE`（请同步后重新选择；保留草稿）。
- 必要父轮完整内容超出预算：HTTP413 A0431 `CONVERSATION_CONTEXT_TOO_LARGE`（请选择更短的回答或重新独立提问；不假装承接）。
- 同requestId不同上下文选择：沿用HTTP409 A0455；活动轮限制、快照复用、重试/取消原规则不变。

## 所有权文件表（计划）
- server/common/.../multiagent/MultiAgentModels.java（CreateRequest、Request、Execution与冻结上下文类型）
- server/common/.../multiagent/ConversationContexts.java（有界完整上下文公共校验）
- server/common/.../enums/ErrorCode.java
- server/app/.../agent/TutorRequestModels.java、TutorRequestServiceImpl.java
- server/learning/.../model/dataobject/MultiAgentRows.java、mapper/MultiAgentMapper.java、service/impl/PostgresTutorLedger.java
- server/app/src/main/resources/db/migration/V2026102601__conversation_context.sql
- server/agent/.../pipeline/RoleProtocol.java、DefaultRolePipeline.java、TutoringPipeline.java、RolePrompts.java
- server/app/src/test/java/... 定向单元/集成测试源（本agent不启动共享验收服务/DB/provider）
- docs/design/multi-agent-conversation-context-v1.md
- client/common/src/main/ets/features/multiagent/LearningV2Models.ets
- client/common/src/main/ets/features/multiagent/LearningV2Api.ets
- 必要客户端公共API解析回归测试；不改Phone组件、渲染器、Tablet。


## 来源权限与保留清理
冻结轮包含replySourceId/replyVersion。来源删除、版本WITHDRAWN/purge或独立journal重放会清空引用该版本的新冻结副本；同账号其他题及无引用请求不受影响。触发器只清理正文副本，不直接改run/attempt终态。随后每次查询/execution均在账号锁内复用既有取消、fence、closeSteps与RUN_CANCEL事件；已在途provider调用不重发，原finishProviderCall仍可释放预算，迟到结果不能发布。正常重试的完整上下文保持不变，权限撤回例外明确失效，不能重新选择替代父轮。
