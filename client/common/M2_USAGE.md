# M2 共享接口接线

M2 公共网络、原生画布、本机草稿和完整分块导出已交付源码。未就绪时 controller 明确返回失败。
C2 开发树只做静态差异检查与本地 SDK 签名复核；构建、网络和设备验收由 C4 独立执行，不能把源码交付当成功验收。

Tablet 从 `../features/canvas/TabletCanvasWorkspace` 引入
`TabletCanvasWorkspace`、`TabletCanvasController`、`CanvasWorkspaceState`。
在父组件持有一个稳定 `new TabletCanvasController()`，挂载：

```ts
TabletCanvasWorkspace({
  question: detail,
  controller: this.canvasController,
  onStateChange: (state: CanvasWorkspaceState) => { this.canvasState = state; },
  onSessionExpired: () => { this.on_session_expired(); }
})
  .width('100%')
  .height('100%')
```

此工作区包括固定题面与笔迹，外层不要并排放独立 Markdown 与空白纸。
C2 内部工具栏负责笔、整笔橡皮、撤销、移动/双指缩放。
A-T 外层按钮调用 `save()`、`markCompleted()`、`continueWriting()`，返回
`Promise<AuthResult<SolutionDetail>>`；返回前 `flushLocal()` 返回 `Promise<AuthResult<boolean>>`。
每次调用先处理 isSuccess/error，禁止自行伪造 fileRef 或成功状态。

状态为 isReady/isBusy/hasUnsavedChanges/hasSavedSolution/canComplete/isCompleted/canUndo/
localDraftSaved/errorMessage。保存按钮 ready 且 !busy 且 !completed；完成按钮 canComplete 且 !busy；
继续按钮 completed 且 !busy。完成接口必须针对最新成功保存的 fileRef；有未保存改动时先保存。

common 的 `QuestionSummary`/`QuestionDetail` 新增 `answerState: AnswerState`；
`QuestionCollection` 新增 `parentCollectionId: string|null`。现有构造参数兼容，末尾新参数有默认值。
AnswerState 三值为 NOT_STARTED/IN_PROGRESS/COMPLETED；同级题集数只统计直接归属。
C2 唯一维护共用 Canvas/Solution 类型与网络；页面不得复制另一份 schema。

纯查看器消费 canvas.width/height 和完整 blocks。合成块 x/y 非负，相对完整范围；
canvas.originX/originY 保留编辑世界原点。Phone 图像源由鉴权下载的私有 file:// 缓存提供，
documentKey 为 fileRef；不能将 Bearer 放 URL，不能从 strokes 重画或重新排题面。


## 已实现的 common 网络与缓存

以 C1 冻结契约 `3a9a72a24b2e3283312bc6f0d32ffc0ee78844df` 为准。
`LearningApi` 新增（均第一个参数 token）：

- `createCollection(token,name,parentCollectionId=null): Promise<AuthResult<QuestionCollection>>`
- `renameCollection(token,collectionId,name): Promise<AuthResult<QuestionCollection>>`
- `moveQuestion(token,questionId,collectionId): Promise<AuthResult<QuestionDetail>>`

`SolutionApi.create_default()` 或 `new SolutionApi(baseUrl)`：

- `getAnswer(token,questionId): Promise<AuthResult<SolutionDetail>>`
- `saveAnswer(token,questionId,upload:FrozenAnswerUpload): Promise<AuthResult<SolutionDetail>>`
- `setAnswerState(token,questionId,fileRef,state:AnswerState): Promise<AuthResult<SolutionDetail>>`
- `downloadBlock(token,accountId,questionId,fileRef,block:CanvasImageBlock,filesDir,questionRaster=false): Promise<AuthResult<string>>`

`downloadBlock` 返回已鉴权、SHA256校验并写入私有缓存的 `file://` 字符串，可直接给 native Image source。
`filesDir` 取当前 UIAbilityContext.filesDir，不传用户输入路径；questionRaster=false 对应完整合成块，
true 对应 questionLayout.rasterBlocks 的干净题面底图。缓存按账号/questionId/fileRef/index隔离。
旧 fileRef 404 时重新拉 manifest，不把两份文档的图块混用。

GET 已保存作答必须有 manifest；保存/状态的200回执允许不含manifest，统一补null，保留原fileRef/state/savedAt。
保存包的 manifestJson 是冻结 UTF-8 文本，files 为 {partName,filePath}[]，包含两组PNG；不重新序列化重试包。
每次 await 后仍必须检查原token/组件存活，真实401按现有条件清理会话；失败不当空作答。

缓存命中仍会重新读取当前作答并校验 fileRef/图块摘要；不可离线复用旧云端引用。上传前逐文件复核冻结 manifest 的摘要，重试保留原 manifestJson 和文件。JSON 响应上限16MiB（含 manifest envelope），PNG 上限16MiB。


## 原生画布首轮接收

- 题面固定使用现有 Markdown/texmath/KaTeX 资源，等待字体与两帧绘制；逐格平移生成干净题面 PNG。资源白名单和 CSP 禁止外网及图片加载。渲染用 CSS 放开长公式、代码和表格的内层裁切。宿主按账号/题号/请求代次重建；异步结果只回到原上下文。
- 同一个 Canvas 绘制底图和世界坐标笔迹；笔、整笔擦除、撤销（当前挂载最多50次）、移动、双指缩放、缩放按钮与全览在内部工具栏。完成/保存期间不能改笔迹，仍可移动查看。只缓存当前视口题面，导出计算全部题面与笔迹半笔宽外延，包含负坐标和屏外内容。
- 本机目录 `filesDir/m2-canvas/{accountId}/{questionId}`。每个手势完成后将可编辑文档、底图路径、视图、baseFileRef、冻结待上传包写完/fsync/rename；恢复先校验归属和底图摘要。云端失败不删除草稿。外层所有返回/换题/退出操作须先 await `flushLocal()`，失败时停留当前页面；销毁回调仅能尽力补写，不能替代这个门禁。
- 一次保存冻结原始 JSON 和请求目录下两组 PNG；上传前重新校验所有摘要。超时/5xx 保留原包重试，409 保留草稿并提示手动刷新；不自动更新 base 覆盖。手动刷新先将旧本机草稿写到唯一 `preserved/*.json`，没有历史版本浏览或合并界面。文件暂不做后台回收。
- `markCompleted()` 如有脏草稿会先完整保存，再对当前 fileRef 调用状态接口；`continueWriting()` 收到真实 IN_PROGRESS 回执后才解锁。纯本机落盘不能完成。

C4 首轮优先：ArkTS 编译/封装、Web 全页快照尺寸、超长公式最右端、长题最底端、负坐标屏外笔迹；保存后 GET/两组 PNG 回读、完成/继续、本机重启恢复。每项必须记录真实结果；未运行构建、HTTP、HDC、签名、安装或设备步骤。

API24 兼容：common 的题集改名、单题移动、作答状态切换调用同路径 POST 别名，JSON 与返回语义不变。C1 保留 PATCH 供 Web；NetworkKit 的 PATCH 枚举 since26，因此 common 不以断言绕过兼容性，也不切换 RCP 或放宽明文网络策略。对应服务端兼容别名必须先接收再验证。
