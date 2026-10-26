# Web 学习笔记契约

基线 `d37297f60f4380d40e8445d84de3090b696da513`。适用 Web C 端、笔记服务端及 BFF。手机和平板契约继续有效；不增加依赖、环境变量或模型调用。

## 正文与归类

原 `GET/POST /api/v2/learning/notes`、`GET/PUT/DELETE /notes/{noteId}` 保留。旧双字段请求仍为 `{title, bodyMarkdown}`；正文上限 64 KiB UTF-8，CRLF 规范化、创建 UUID 幂等键、强 ETag 与 If-Match、412 合并、删除撤权均保留。未传或 null context 的创建逐字沿用旧请求指纹算法。

创建可以附带可选 context：

```json
{
  "title": "配方法中的常数补偿",
  "bodyMarkdown": "补上多少，就减去多少。$x^2+2x=(x+1)^2-1$",
  "context": {
    "notebookId": null,
    "questionId": "17",
    "excerpt": {"resultId": "11111111-1111-4111-8111-111111111111", "turnId": null, "text": "原文的一整段"}
  }
}
```

context 的 notebookId/questionId/excerpt 可以为 null；自由笔记可完全不传 context。excerpt 的 resultId 与 turnId 必须且只能有一个，text 为按连续两个以上换行分隔后的一整段原 Markdown，最多 16 KiB。题目、笔记本及辅导归属由账号上下文在服务端校验；正文、幂等记录与关系在同一事务提交。实际带 context 的请求指纹包含规范化后的内容和关系；同 key 改关系返回 409。重放先找旧 key，再校验仅新建需要的外部关系，所以笔记本已删或原辅导撤权后仍可核对原 noteId。

Note 响应追加 context（旧字段不变）：`{notebookId,questionId,revision,excerpt}`。excerpt 响应包含 resultId/turnId、requestId、sourceId/version、versionHash、text、available。V2 固定来源版本；旧辅导以已完成 turn 和整篇原文 SHA-256 固定版本身份，不新增来源发布或 embedding。当前来源或固定旧版本撤权、清除后返回 `available=false,text=null`；旧辅导原文变化同样不可访问。

`PUT /notes/{noteId}/context` 仅接受 `{notebookId: UUID|null}`，要求 `If-Match: "context:{noteId}:{revision}"`。它只改变归类和独立 revision，不生成 source_version/outbox。题目与摘录出处在创建时固定。正文 PUT 不允许改 context；旧正文 PUT 保留全部元数据。正文 `source.originId` 始终为 noteId。

## 单层笔记本

- `GET /api/v2/learning/notebooks` → `{items:[{notebookId,name,revision,updatedAt}]}`。
- `POST /notebooks`，正文 `{name}`，UUID Idempotency-Key 作为 notebookId；名称 1–64 字。
- `PUT /notebooks/{id}`，正文 `{name}`，`If-Match: "notebook:{id}:{revision}"`。
- `DELETE /notebooks/{id}?confirm=true`，同独立 If-Match。缺确认拒绝。账号行锁内先归类回未归类，再删笔记本；不级联删除笔记或来源。

## 搜索与分页

`GET /notes?q=关键词&notebookId=UUID|unfiled&questionId=17&limit=20&cursor=...`。过滤可组合；q 去两端空白，最多 200 字，查询标题和当前正文。SQL 参数化，`%`、`_`、反斜线按用户文字搜索；不会调用 embedding。按正文最近更新时间与 noteId 降序，服务端全量范围键集分页。HMAC 游标绑定账号、规范化搜索/归类/题目过滤和排序锚点；跨账号、换过滤或篡改拒绝。无新增过滤时保持原 list 与旧游标逻辑。

## 本机草稿与交互

身份接口确认 accountId 后才读取 `bl.notes.draft.v1:{accountId}:{draftId}`。不存认证信息。恢复时为潜在编辑分配独立 draftId；内容、关系、原创建请求和正文基线均未变且原快照仍完整时复用原保护，不写无变化副本；实际编辑或原快照失效才持久化独立稿。成功清理后再换新 ID，避免多标签覆盖及 revision ABA。仅原账号认证后恢复自己的草稿。

输入同步保存到本机；云端只由用户显式保存。创建请求发出前，原 key、原请求体和结果待核对状态必须成功落盘，否则阻止首次 POST 并保留输入。结果未知使用原请求重放；已知 noteId 后只读/改同篇。保存中允许继续输入，成功只清理对应草稿版本，后续编辑继续保留。旧 baseVersion 与云端不一致时恢复为待合并稿，不自动覆盖；读取新云版本不会改写本机原基线，连续刷新仍需显式合并。

存储 Quota/Security 异常会明确提示并阻止内部切篇、链接导航和摘记关闭。离页同步保留成功则允许刷新恢复，失败才触发浏览器警告。显式丢弃才删除未保存草稿。浏览器 Back/Forward 的 popstate 在捕获阶段同步保留必要草稿，早于客户端路由卸载，不阻止或接管历史导航；未变原稿仍不新增副本，已显式丢弃的稿不会重建。若本机存储已经失败，纯客户端历史导航仍可能丢仅内存中的快照；此时应保留页面、保存云端或复制正文。

独立空间左侧搜索/笔记本/本机草稿，右侧默认阅读；窄屏切为列表或单篇。技术详情与来源同步状态折叠。题目“记一笔”和辅导整段摘录使用原生 dialog，保留底下作答/辅导 DOM、tab、滚动和缩放状态；关闭恢复原焦点。笔记可回原题与对应辅导记录。

## 迁移与验证

迁移 `V2026102602__learning_note_workspace.sql` 新建 `ma_notebook`、`ma_note_context`，将旧笔记补为未归类，不改原来源、历史正文、请求指纹和索引事件。2601 留给并行会话上下文迁移。

验证可使用 Vitest/testing-library、Maven/JUnit、PostgreSQL+Redis Testcontainers；真实模型与 embedding 的行为需单独验证。
