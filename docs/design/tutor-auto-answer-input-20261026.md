# 自动选择作答输入（2026-10-26）

本次产品行为：无同账号可用平板也允许基于当前题面、文字问题及合法既有对话发起辅导；检测到同账号、同题且可冻结的平板时才获取新作答。没有作答输入不等于学生提交空白作答。

## 请求契约

Phone 默认创建 `snapshotInput: {kind: "AUTO"}`。服务端在账号锁保护的创建事务内读取平板租约，持久化实际选择和 `inputSelectionReason`：

| 可用性 | 实际 snapshotInput.kind | 原因 | 初始状态 |
| --- | --- | --- | --- |
| 同账号、同题新鲜可冻结租约 | NEW_CAPTURE | TABLET_AVAILABLE | WAITING_INPUT |
| 该账号从未上报租约 | NO_ANSWER | NO_DEVICE | QUEUED |
| 已知租约均释放或过期 | NO_ANSWER | OFFLINE | QUEUED |
| 有新鲜租约，但均在其他题 | NO_ANSWER | OTHER_QUESTION | QUEUED |

`AUTO` 是创建命令，返回值只包含解析后的实际输入类型。`requested_snapshot_kind` 保存原始选择，幂等重放比较原始命令、题目、问题、模式、父轮及复用来源；设备状态改变不会重新选择，也不会另建运行。鉴权、协议、数据库或网络失败均保留失败/结果未知语义，不转换为 `NO_DEVICE`。

原 `NEW_CAPTURE` 与 `REUSE_REQUEST` 继续有效；省略 snapshotInput 仍按旧契约使用 NEW_CAPTURE。显式复用必须引用同账号同题、终态且有完整快照的请求。NO_ANSWER 不是允许客户端直接强制提交的创建类型。

NO_ANSWER 在创建事务内直接生成真实 run，冻结当时题面；snapshotOriginRequestId、snapshotFileRef、snapshotSourceId、snapshotVersion 均为 null。不会创建快照来源、空白 PNG 或替代 UUID。重试沿用冻结题面和父轮，不重新探测设备或重新读取后续编辑的题面。原取消、结果未知确认及显式重试协议保持。

## 最小平板可用性

认证接口 `POST /api/v2/learning/tablet-presence`：

```json
{"sessionId":"UUID","sequence":1,"questionId":"123","active":true}
```

账号只取自认证上下文。active=true 时题目必须属于该账号；active=false 时 questionId 必须为 null。响应为 `{sessionId, sequence, questionId, active, leaseExpiresAt}`。正整数 sequence 上限为 JavaScript 安全整数；同序号同内容只重放，不续租；低序号返回已有值；同序号不同内容冲突。

Tablet 前台画布实际绑定可冻结句柄后，每 8 秒上报一次，服务端租约为 30 秒。进程内 session 和序号跨桥实例延续；切题、后台、账号切换与销毁发送更高序号的释放。服务端保留释放序号，防止迟到心跳复活旧题。客户端也用账号、题目、token 和异步代次挡住旧响应。

`GET /api/v2/learning/questions/{questionId}/tablet-availability` 返回 `{available, reason, leaseExpiresAt}`，复用创建事务的判定逻辑。Phone 创建不依赖一次预查询，避免查询与提交之间的状态变化。

这里的可用性表示最近 30 秒内上报过可冻结状态，不承诺瞬时物理在线。首次心跳成功之前没有可用租约；断网或释放失败由租约到期收回。若创建已选定 NEW_CAPTURE 后平板离线，该请求保持原冻结/等待/取消/超时语义，不悄悄改成纯文本。

## 模型与历史

模型输入显式区分 `AnswerInputKind.NONE` 与 `SNAPSHOT`。NONE 只有冻结题面、问题及受限历史，不带图、笔迹、OCR 或快照引用；诊断状态为 `NO_ANSWER`。真实零笔快照仍有完整文件和图片，hasHandwriting=false，诊断状态为 `NO_WRITING`。

四角色及返工、审核、输入摘要均携带输入类型。提示词禁止在 NONE 情况下声称看过当前手写，程序校验拒绝明确虚构手写或空白提交的输出。历史成功纯文本轮次允许 snapshotFileRef=null；仍冻结经账号、题目、发布状态校验的父轮。历史引用不会被当成本轮快照事实，本轮不存在的快照不能成为引用。

角色协议升至 `p3-roles-v9-answer-input`。已保存角色输入摘要的旧运行仍受原摘要匹配保护，不迁移或伪造旧确认输出；集成时应结束旧在途运行，再启用统一的新服务端/客户端候选。

## 客户端交互

Phone 默认自动选择；“沿用该作答”仅用于下一轮，并在输入区显示可取消的紧凑标记。发送后消费该选择，旧草稿恢复时清除遗留选择；已经生成的未确认 createIntent 保留原请求身份和输入，继续查询原请求。没有恢复已删除的整块 footer，顶部标题栏及底部 Tab 保持原样。

Phone、Tablet 及 Web 历史读取都接受 NO_ANSWER 的“无快照、有运行”，并显示明确无作答文案。在线真实零笔仍走现有完整冻结上传及笔画结束时机。

## 迁移与交付边界

本增量使用 `V2026102604__text_only_tutor_presence.sql`，新增租约表及请求选择/冻结字段，旧 REUSE 数据按实际 snapshot_kind 回填。交接基线中的 `V2026102601__conversation_context.sql` 原文保持不变。

本分支从 d37297f 加 102 份冻结交接文件建立；独立基线提交为 `4863f156ab4e87cbb295957fe0885f540affe2c7`。它不等同于当前 develop 或已安装候选。协调会话将在最终集成时处理 develop 已有 2602 与未合入对话迁移的顺序；本任务的小样本仅验证自身隔离数据库，不代表 develop 升级、真机或真实模型验收。
