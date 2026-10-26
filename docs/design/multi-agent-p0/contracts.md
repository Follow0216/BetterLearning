# 多 Agent 与个人学习引用 P0 契约 v2

机器契约见 [openapi.yaml](openapi.yaml)。本文件记录跨模块语义；字段名、路径、状态及竞争处理如需改动，应同步更新两份契约。所有公开 ID 均为小写 UUID 字符串（既有 `questionId` 仍为十进制正整数字符串），时间为带时区 ISO 8601。账号只从认证 principal 获取，客户端不得提供 `accountId`。

## 产品范围及旧入口

- 首版 Phone 发问并查看提示/引用；Tablet 仅当前题前台轮询、抬笔后冻结并上传；Web 查看辅导、创建/编辑 Markdown 与公式笔记；Phone/Tablet 只读笔记。默认 `HINT`；`FULL_SOLUTION` 仅在客户端明确选择且服务端确认字段后执行，不能由模型、重试或默认值自行升级。模型不得把笔记、旧作答或旧诊断当标准答案。
- 保留 M3 旧 `/api/v1/learning/.../tutor-turns` 路由与旧记录只读兼容；新请求走本文件 `/api/v2/learning/.../tutor-requests`。切换开关由后续实施门禁确定，不能让同一客户端动作同时写新旧入口。**新旧入口共享账号级活动互斥**，不能各有一个活跃请求；迁移先实现统一互斥再打开新写入口。一个题可同时读旧历史和新历史，但用 `kind` 区分，不合并 ID 或重放旧模型请求。旧 pending 与新 pending 不混用；新客户端只轮询新 pending。旧 M3 的已保存冻结文件仍按旧授权读取，新接口不把旧 `fileRef` 视为新 request 的快照。
- M2 当前作答 `fileRef` 仍可覆盖；每次**明确保存**在同一数据库提交中产生不可变 `sourceVersion` 及索引 outbox，`SAVE_DRAFT` 与 `COMPLETE` 都建新版本并标 `answerState`。从已完成作答执行 `CONTINUE` 时，以原已保存内容创建新 `DRAFT` sourceVersion 和 outbox，不把随后尚未明确保存的新笔迹入库/索引；后续保存再建版本。草稿/完成是来源状态而非正确性判定，旧作答不能被当标准答案。未明确保存的画布本机草稿不入历史。新辅导快照另存且不可变，不调用 M2 保存，`TUTOR_SNAPSHOT` 默认不进普通历史 RAG。

## 数据与唯一性

| 对象 | 最小字段和主键 | 约束与生命周期 |
| --- | --- | --- |
| `Source` | `sourceId`、`accountId`、`type=QUESTION\|SAVED_ANSWER\|TUTOR_SNAPSHOT\|TUTOR_REPLY\|NOTE`、`originId`、`status=ACTIVE\|DELETED`、`createdAt` | `(accountId,type,originId)` 唯一；保存作答、辅导冻结和回答严格分源。删除先改状态并阻止读取，再异步移除索引；问题删除/失权级联逻辑撤销引用，不把 `sourceId` 分配给另一账号。 |
| `SourceVersion` | `(sourceId,version)`、`contentSha256`、`createdAt`、`status=INDEX_PENDING\|READY\|FAILED\|WITHDRAWN`、`answerState=DRAFT\|COMPLETED\|null`、`fileRef?` | `version` 是每 source 从 1 单调递增的 bigint，内容及原冻结对象不可原位改；笔记更新用 ETag/If-Match CAS，版本不符 412。笔记创建使用 `Idempotency-Key`，同键同内容返回原笔记，异内容 409。旧版本只要仍被 request/result/citation 引用就保留正文和文件；删除/失权立即撤销所有旧版本读权限，索引清理异步，物理清除建议 7 天内完成（待审核）。 |
| `Anchor` | `anchorId`、`sourceId`、`version`、`kind=MARKDOWN_BLOCK\|HANDWRITING_STEP\|QUESTION_BLOCK`、`ordinal`、`contentSha256`、`locator` | `(sourceId,version,kind,ordinal)` 唯一，`anchorId` 在该版本稳定。Markdown `locator` 指向解析后的段落/公式块和字符范围；手写可跨多个 manifest 块，定位器含每块索引、资产 URL、块世界坐标边界、该块实际像素宽高及目标区域世界坐标边界，笔划 ID 可选，保留压感 `Point.pressure` 可空且不重采样。客户端只能按所给变换渲染，不猜题面/合成图密度。无法完整覆盖目标步骤则不产生可点击引用。 |
| `Chunk` | `chunkId`、`anchorId`、`sourceId`、`version`、`text`、`methodTags[]`、`indexRevision` | 分段不得跨 source/version；全文与 pgvector 均为可重建索引，主库 `SourceVersion/Anchor` 是事实源。普通历史召回先限定当前账号、ACTIVE source、**当前 version 且 READY**、允许的来源类型，再全文+向量候选，以方法和推理步骤重排，题面相似只作辅助。编辑后新版本 INDEX_PENDING/FAILED 时不回退召回旧版本；旧版本仅供已发布引用 resolve 或用户显式浏览历史。索引迟到、删除、编辑都不得越过主库实时授权。 |
| `TutorRequest` | `requestId`、`accountId`、`questionId`、`prompt`、`mode=HINT\|FULL_SOLUTION`、`snapshotInput=NEW_CAPTURE\|REUSE_REQUEST`、`snapshotOriginRequestId?`、`snapshotFileRef?`、`snapshotSha256?`、`currentRunId?`、`revision` | `(accountId,requestId)` 唯一；请求头 `Idempotency-Key` 与 body `requestId` 必须相同，同键同题/同 prompt/同 mode/同 snapshotInput 返回原对象，异参 409。`NEW_CAPTURE` 先创建 `WAITING_INPUT` 且无 fileRef，完整冻结后 CAS 绑定并排队首 run；`REUSE_REQUEST` 在创建事务内授权并绑定前序快照，直接 `QUEUED`。重复原包返回原对象，不替换快照；同键不同摘要 409。request 展示状态为当前 run 状态投影，retry 指向新 run，不回写旧 run。 |
| `Run` | `runId`、`requestId`、`accountId`、`retryOfRunId?`、`status`、`revision`、`cancelRequestedAt?`、`reason?`、`resultId?` | `(requestId,runNo)` 唯一，request 对 run 为 1:N，当前指针 `currentRunId` CAS 更新。每个 run 终态不可回退。Temporal workflowId 固定为 `runId`，DB 是客户端状态事实源，Temporal 历史是执行证据。模型/检索调用不持有 DB 事务。 |
| `Step` | `stepId`、`runId`、`role=RETRIEVE\|SOLVE\|DIAGNOSE\|TEACH\|REVIEW`、`status`、`revision`、`inputDigest`、`outputDigest?` | `(runId,role,sequence)` 唯一；检索为确定性工具，不计 LLM。`SOLVE` 不以旧解为权威，`DIAGNOSE` 只根据当前题/冻结图和可见证据，`TEACH` 默认为提示，`REVIEW` 校验解法、证据、引用与模式。 |
| `Attempt` | `attemptId`、`stepId`、`attemptNo`、`status`、`providerCallState=NOT_SENT\|CALL_INTENT\|CONFIRMED\|UNKNOWN`、`fence`、`startedAt`、`endedAt?` | `(stepId,attemptNo)` 唯一。调用前持久化 `CALL_INTENT`；响应不可确认且可能已收费时旧 run 终结为 `FAILED/reason=OUTCOME_UNKNOWN`，不得自动重发。用户显式 retry 建新 run/新 step/新 attempt，复用同一快照；旧结果由 currentRunId/revision/fence 拒绝。 |
| `Evidence` | `evidenceId`、`runId`、`stepId`、`type=CURRENT_SNAPSHOT\|HISTORY\|DERIVATION`、`sourceId?`、`version?`、`anchorId?`、`claim`、`confidence?` | 当前作答可见证据与历史检索证据分开；模型推断不能自动成为已掌握事实。置信度是模型建议，不替代来源定位。 |
| `Result` | `resultId`、`runId`、`mode`、`status=VERIFIED\|WITHHELD`、`bodyMarkdown`、`citations[]`、`createdAt` | 每 run 最多一个对外发布的 VERIFIED 结果，发布与 run SUCCEEDED 在同一 CAS 事务；review 未通过为 WITHHELD，不公开不可信答案。正文可有编号引用标记，不内嵌来源原文副本，防止源删除后从历史回答泄露。 |
| `Citation` | `citationId`、`resultId`、`sourceId`、`version`、`anchorId`、`label` | `(resultId,citationId)` 唯一；Result 中仅存元数据，`quotedText` 如需展示只可在实时授权后的 resolve 响应中返回。旧作答须标“用户历史作答”，旧模型输出须标“历史辅导建议”，不得标标准答案。无命中省略引用，不伪造。 |
| `Outbox` | `eventId`、`accountId`、`aggregateType`、`aggregateId`、`aggregateVersion`、`kind`、`payloadDigest`、`status` | 与源保存/编辑/删除或结果发布同事务写入；结果发布通过同事务的 `TUTOR_REPLY/SOURCE_VERSION_CREATED` 通知索引，不再另发无消费者的 `RESULT_PUBLISHED`。待投递仅 `PENDING/FAILED`，`DELIVERED` 表示消费完成；`RETIRED` 仅用于经 V2026102507 完整发布链核验的旧 RESULT 事件，保留原字段及退役时间、原因、迁移来源、原状态，不代表投递成功；`(aggregateType,aggregateId,aggregateVersion,kind)` 唯一。消费者以 `eventId` 去重并按 version CAS 更新索引；旧事件不得覆盖新版本，失败可重投，撤销权限先于索引清理生效。 |

索引只保存必要的分段、方法标签和向量；不将原图二进制写进 pgvector。`TUTOR_SNAPSHOT` 的 `originId` 为首次 `NEW_CAPTURE` 的 requestId；复用链固定同一个原始源版本，`snapshotOriginRequestId` 记录该原始 request，不能把 M2 当前保存当辅导快照。原文件在 `APP_FILES_ROOT` 的保留/清理需按源版本引用计数与删除政策定义，当前版本覆盖不能删除被辅导快照或引用所需的旧版本。每个读取、文件 GET、resolve、列表和后台索引消费均以 `accountId` 过滤，不信任客户端路径与引用 ID。

## 请求、状态、竞争

1. Phone `POST /tutor-requests` 有两种显式输入。缺省 `snapshotInput=NEW_CAPTURE`：200 返回 `WAITING_INPUT/currentRunId=null`，之后 Tablet pending；Tablet 读当前题 pending，落笔中等抬笔，完整冻结题面与全部笔迹（含视口外、负坐标、可空压力值），保留原字节直至服务器确认。`POST /tutor-requests/{requestId}/snapshot` 的 multipart manifest 沿 M2 `SaveManifest`，`saveRequestId=requestId`、`baseFileRef=null`；服务端先校验全部块和摘要、原子发布文件，再绑定，文件失败不启动 run。空笔迹但题面完整为有效输入。绑定后 pending 消失。Tablet 可继续书写，但已绑定快照永不变化；**后续新写内容**若要进入辅导，Phone 必须以 `NEW_CAPTURE` 新建 request，Tablet 重新冻结成新快照。
2. 追问**同一冻结时刻**可新建 request 并显式给 `snapshotInput={kind:REUSE_REQUEST,sourceRequestId}`。服务端在同一事务验证前序 request 属当前账号、同一 questionId、已终结、已绑定不可变快照、来源仍 ACTIVE 且文件/题面版本可用；`sourceRequestId` 不得等于新 requestId。可从既有复用链追问，但必须解到首次 `NEW_CAPTURE` 的原始 request 并固定其源版本及文件保留。成功 200 返回新 request `QUEUED/currentRunId!=null`，无 Tablet pending，也不能再 POST snapshot。跨账号、跨题或源已失效不得复用，向外统一 404/409 安全错误。新的 prompt/mode 有新的 requestId；后续书写不会进入此旧快照。显式 retry 则针对当前 FAILED/TIMED_OUT run 新建 run，继续复用当前 request 已绑定快照，**不新建 request**。
3. request 在未绑定前为 `WAITING_INPUT`，可因取消或输入超时在无 run 时终结为 `CANCELLED/TIMED_OUT`；绑定后创建首 run。每个 run 的稳定状态为 `QUEUED → RUNNING → SUCCEEDED | FAILED | CANCELLED | TIMED_OUT`，取消中可显示 `CANCELLING`，但 `CANCELLING` 不是终态。首 run 和 retry run 均遵循此图；旧 run/step/attempt 终态不可回退。绑定后的 request `status` 是当前 run 状态投影；用户 retry 后 `currentRunId` 改指新 run。上传后的排队由 outbox/worker 驱动，不依赖一次 HTTP 响应。建议期限（待审核）：输入等待 5 分钟、排队 2 分钟、单次模型 120 秒、整 run 10 分钟；输入/排队/整 run 截止为 `TIMED_OUT`，已发模型调用的 120 秒超时若结果不明则 `FAILED/OUTCOME_UNKNOWN`，不得盲重试。
4. `cancel` 请求体必须带 `expectedRunId`（可为 null；仅 `WAITING_INPUT` 使用 null）。服务端在同一账号锁/事务内 CAS 核对目标与 `currentRunId`；旧取消包若迟到且已进入新 retry run，返回 409，绝不能取消新 run。相同目标的重复 cancel 幂等返回原结果；null 目标与快照绑定竞争时，取消先提交则阻止绑定，绑定先提交则 null 取消返回 409 让客户端刷新。目标 run 运行中 CAS 标 `cancelRequestedAt`、显示 `CANCELLING` 并通知 Temporal，Activity 见取消标记不得发布。complete/cancel 只允许一个终态 CAS：complete 先提交则同目标 cancel 返回已成功 run；cancel 先提交则迟到 complete 因 currentRunId/revision/fence 不匹配被丢弃。取消不能保证供应商已发出的调用不计费。
5. 模型发送前持久化 `CALL_INTENT`；若发送后超时、节点崩溃或响应不可确认，当前 run 结束 `FAILED/reason=OUTCOME_UNKNOWN`，不自动重发收费调用。只有 `targetRunId=currentRunId` 且目标 run 为 `FAILED/TIMED_OUT`、快照仍有效时，用户才可显式 `POST retry`；`SUCCEEDED/CANCELLED` 须新建 request，可选 `NEW_CAPTURE` 或合法 `REUSE_REQUEST`。retry 以 `Idempotency-Key` 去重，新建 `retryOfRunId=targetRunId` 的 run，`currentRunId` CAS 指向它，复用已绑定快照；同键返回同新 run，异参或旧目标返回 409。普通读取/索引可安全重试；旧 run 的迟到结果不能覆盖新 run。若调用能证明未发送才允许安全自动重试。
6. 新旧入口共用同账号活动互斥；一个账号不得同时存在旧 M3 活动 turn 与新 request 当前活动 run。内部 SOLVE/DIAGNOSE 可有限并行，不占新的用户 turn。正常 run 为 SOLVE/DIAGNOSE/TEACH/REVIEW 四次 LLM 调用，检索工具不计入；在线调用硬上限八次，只允许一次有针对性的返工回路，含所有角色重调/异常调用；达到预算以 `FAILED` 终结，不无限排队。另设每账号/全局并发、图像字节与费用上限。默认 `HINT` 不披露完整解答；`FULL_SOLUTION` 须服务端校验请求中的显式模式。

同题历史 `GET /tutor-requests` 以 `(createdAt DESC,requestId DESC)` 稳定键集分页，`limit` 默认 20/最多 100；`nextCursor` 是绑定账号和 questionId 的不透明游标，`null` 表示无下一页。游标跨账号/跨题复用或篡改返回 400；后插入的新请求不重复旧页。列表每项只显示 request 当前 run 摘要，详情 `GET /{requestId}` 给按 `runNo` 升序的全部 run，以免 retry 覆盖历史。创建接口两种模式均返回 200：`NEW_CAPTURE→WAITING_INPUT/currentRunId=null`，`REUSE_REQUEST→QUEUED/currentRunId` 非空。省略 `snapshotInput` 与显式 `NEW_CAPTURE` 先规范化再比较幂等指纹。

### 执行期限与调度边界

request/run 持久化 `inputDeadlineAt/queueDeadlineAt/runDeadlineAt` 的适用值，以服务端时间判定。独立于 Temporal worker 的业务对账器负责关闭逾期等待/排队任务，dispatcher 在启动前再次校验期限；Temporal 不可用不能导致 QUEUED 永久占用账号槽位。取消一旦提交即禁止发布；`CANCELLING` 仅表示执行侧清理中，建议最长 30 秒后业务状态收敛为 CANCELLED 并释放交互槽位，供应商可能仍在结束旧调用，旧 fence 永远无发布权。已取消但仍在途的外部调用继续计入账号/全局执行并发与费用预算，直到确认结束或执行超时，不能靠连续取消绕过限额。

业务 `runId` 映射 Temporal `workflowId`；Temporal 自身的 execution run ID 是另一个运维标识，不能替代业务 runId。首版模型 Activity 明确设置 SDK 自动 retry 最大 1 次尝试、Workflow 不配置自动重开；安全重试由账本判定后显式调度。普通读取/索引 Activity 可用有界退避，不能把相同策略应用到收费模型调用。此处为后续实施契约，P0 实验替身的 SDK 默认选项不可直接复制为生产重试策略。

## 检索与引用模态框

冻结绑定后，RETRIEVE 用题干、提问与当前作答推得的**方法候选**检索个人历史；只取同账号 ACTIVE source 的当前 READY 版本，类型限 `QUESTION/SAVED_ANSWER/NOTE/TUTOR_REPLY`。`TUTOR_SNAPSHOT` 是本轮当前证据，不进普通历史 RAG；编辑后当前版本尚未 READY 时不回退召回旧版本，旧版本只供已发布引用 resolve 或用户显式看历史。检索结果包含 source/version/anchor/方法标签/片段和可信来源类型，数量与总字符数有上限；不会把整库上下文送模型。旧笔记和旧解可有错，SOLVE/DIAGNOSE 先独立判断，TEACH 才把可信历史作为“你此前在……使用过此思路”的例子。REVIEW 逐条验证结果引用；无法定位、权限变更或来源失效则删引用，若解释依赖该引用则返工或不发布。

点击引用调用 `GET /api/v2/learning/citations/{citationId}/resolve`，服务端实时验证 citation 所属 result **已发布且属当前账号**、source 归属与 ACTIVE 状态、sourceVersion 可见、anchor 的版本和摘要与 citation 完全相符。返回来源创建时间、被引版本创建时间、当前版本号、作答草稿/完成状态。Markdown 返回原始块和 `[startOffset,endOffset)`，偏移以返回块的 UTF-16 code units 计且不得切开代理对；`contentSha256` 对保存时仅统一 CRLF→LF 的原始 UTF-8 块字节计算，公式不改写。`MARKDOWN_BLOCK` 必有 markdown/markdownLocator；`HANDWRITING_STEP` 必有 handwritingLocator；`QUESTION_BLOCK` 必有其中一种。手写定位有一个或多个 `segments`：每段包含 blockIndex、assetUrl、blockWorldBounds、pixelWidth/Height、regionWorldBounds，可选 strokeIds；所有跨块部分须完整覆盖。模态框按每段明确的世界坐标与实际像素尺寸变换并高亮，不跳到当前修订版冒充旧证据。每次资产 GET 再校验当前账号→已发布 result→citation→ACTIVE source→version→anchor 全链及请求的 blockIndex 确属 segments；URL 本身不是凭证。源已删除/撤销或定位不可验证返回 404/409 错误对象，不退化为相似片段，也不显示空白伪引用。

## 验证范围

端到端验证应覆盖 request 创建后快照、复用快照、重复同包/异包、抬笔完整冻结、M2 草稿/完成/CONTINUE 版本、笔记 CAS、方法相同题面不同与题面相同方法不同、无命中、错误旧解、编辑/删除/跨账号、索引乱序、跨块定位、cancel/complete 竞争、旧 cancel 迟到、已发送未知模型调用及显式新 run retry。真机、真实模型语义质量和持久服务故障仍需独立验证。HTTP 错误沿既有 `ApiErrorVO {code,message,traceId,fieldErrors?}`；当前已有 `A0400/A0427/A0200/A0300/A0606/A0455/C0001/C0002/B0001`，新错误码须单独审核，不能在本契约中假称已实现。

建议参数：笔记正文 UTF-8 最多 64 KiB；列表默认 20、最多 100；所有受保护路径要求 Bearer；删除/失权立刻撤销旧版本读权限，物理清除建议 7 天内。待确认的实施参数包括新旧入口灰度/关闭日期、Temporal/pgvector 具体依赖版本与部署预算、方法标签词表和检索质量门槛、供应商是否提供可核验调用幂等键。本文不宣称这些技术已经部署或真实模型已通过。
