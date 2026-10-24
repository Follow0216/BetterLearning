# M1 common 接入

协议来源是 C1 `docs/design/mvp-m1-contract.md`（2026-10-24），本次采用的 SHA256 为
`854d1d8af45dff57398dd8de3afd1a150a6bddbfcbff12ebc652b06b874eac77`。
公共导出统一从 `common` 引入，不依赖 P0 fixture 或页面内部类型。

## 登录与退出

```ts
AuthScreen({
  onAuthenticated: (user: UserProfile) => {
    // 登录及 GET users/me 校验通过后进入当前账号题库。
  },
  onSignedOut: () => {
    // 清除题库、题集、详情、输入快照等当前账号可见状态。
  }
})
```

应用进入题库后的退出继续调用既有 `AuthApi.logout(token)`。204 或 401 可结束该会话；
其他失败保留会话并提示重试。只有 `authSession.clear_session_if_token(token)` 返回 true
时才清除页面数据并回到登录，防止旧请求清掉刚建立的新会话。

## 列表与详情

```ts
const api: LearningApi = LearningApi.create_default();
const token: string | null = authSession.read_access_token();
if (token === null) {
  // 本地已过期：清除可见数据，回到登录；不要发学习请求。
  return;
}
const result: AuthResult<QuestionPage> =
  await api.listQuestions(token, new QuestionListQuery(1, 20, null));
// 先识别已切换账号的响应；该分支不得修改新账号页面。
if (!authSession.matches_access_token(token)) {
  return;
}
if (result.error !== null && result.error.is_unauthorized()) {
  if (authSession.clear_session_if_token(token)) {
    // 清除可见数据并回登录。
  }
  return;
}
if (!authSession.has_valid_session()) {
  authSession.clear_session_if_token(token);
  // 请求期间本地到期：清除可见数据并回登录。
  return;
}
if (result.isSuccess && result.data !== null) {
  // 使用 result.data.items/page/pageSize/total 更新页面。
} else {
  // 网络、503、404、协议错误与真实空列表区分；保留会话并提供重试。
}
```

每个 `await` 后均执行同样的身份检查，包括题集、详情、确认和退出。
`listCollections(token)` 返回 `AuthResult<QuestionCollection[]>`；
`getQuestion(token, questionId)` 返回 `AuthResult<QuestionDetail>`。
题目和真实题集 ID 始终为字符串；`INBOX_COLLECTION_ID` 固定为 `inbox`。
题库全部题目使用 `new QuestionListQuery(page, pageSize, null)`，按题集浏览传第三个参数。
列表显示服务端 `summary`，详情显示 `bodyMarkdown`，不能从摘要重建完整题面。

## 人工确认

```ts
// 首次确认时创建；页面保存此对象，提交中禁用重复按钮。
const input: ManualQuestionInput =
  ManualQuestionInput.create(LearningSubject.HIGHER_MATHEMATICS, bodyMarkdown);
const result: AuthResult<QuestionDetail> = await api.confirmManualQuestion(token, input);
// 确认响应同样执行上面的 token guard，再更新当前账号页面。
// 断网、超时、未知结果后的重试仍传同一个 input，不能换 requestId。
```

正文或学科编辑后重新 `create`；服务端的同 ID 不同内容 409 不作为成功。
所有新题归待整理，客户端不发送 collectionId、title、summary 或 accountId。
`LearningSubject` 仅为 `HIGHER_MATHEMATICS` / `OTHER`，显示名称可用
`learning_subject_label(subject)`。正文不 trim 或改写，长度上限为 20000 UTF-16 code units。
API 不持有令牌、不自动刷新、不自动重试确认，也不修改会话。

## 验证边界

C2 开发树不运行构建、测试、服务或 HDC。共享接口经只读协议/差异复核及
`git diff --check` 检查；真实 Phone/Tablet 构建和端到端行为由 C4 独立 TEST 验证。
Markdown 题面组件及获批的本地资源已作为独立渲染提交提供，入口为：

```ts
QuestionMarkdownView({ bodyMarkdown: detail.bodyMarkdown })
  .width('100%')
  .height('100%')
```

父容器须给定可用高度，题面在 Web 内滚动；无需页面提供 controller 或资源路径。
可选 `fontSize: number`（默认 16，范围 12–32）与 `onRenderError: () => void`（默认空）。
依赖锁、资源哈希与 C4 验收边界见 `M1_RENDERER.md` 和 `renderer-assets.lock.json`。
