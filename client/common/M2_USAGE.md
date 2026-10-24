# M2 共享接口接线

本次接口提交仅固定可引用的 DTO/组件/控制器边界，真实画布、持久化、导出和 HTTP 实现随后交付。
未就绪时 controller 明确返回失败；不要把接口提交当作已可验收的作答功能。

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
