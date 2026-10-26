# M3/M4 共享 HAR 使用说明

本文件说明 HTTP API、DTO、抬笔冻结和顺序发送。未挂载或未就绪时控制器返回失败。

从 `common` 导入 `TutorApi`、`TutorTurnStatus`、`TutorTurn`、`TutorPending`、`FrozenTutorAnswer`、`OcrApi`、`OcrResult`。`new TutorApi(baseUrl)` 或 `TutorApi.create_default()`。

## TutorApi

- `createTurn(token,questionId,requestId,prompt): Promise<AuthResult<TutorTurn>>`，创建/重复均 HTTP200；保留 prompt 原文，账号级一个活动轮，409不自动重发。
- `listTurns(token,questionId): Promise<AuthResult<TutorTurn[]>>`，最近50轮，保持服务端顺序。
- `getTurn(token,questionId,turnId): Promise<AuthResult<TutorTurn>>`。
- `getPending(token,questionId): Promise<AuthResult<TutorPending>>`，成功的 `data.turn=null` 是正常无待处理轮次。
- `uploadFrozenAnswer(token,questionId,turnId,upload:FrozenAnswerUpload): Promise<AuthResult<TutorTurn>>`，Tablet应使用下面的控制器顺序发送入口；不得与普通保存并发。
- `retryTurn(token,questionId,turnId)` / `cancelTurn(token,questionId,turnId)`，返回 `Promise<AuthResult<TutorTurn>>`，空JSON POST；模型重试由服务端复用原轮文件，不调用冻结入口。
- `getFrozenAnswer(token,questionId,turnId): Promise<AuthResult<AnswerManifest>>`，直接 ReadManifest，不包 SolutionDetail；校验题干hash/布局/两组图块。
- `downloadFrozenBlock(token,accountId,questionId,turnId,fileRef,block,filesDir,questionRaster=false): Promise<AuthResult<string>>`，返回鉴权并校验摘要的私有 `file://`；默认合成块，true读取干净题面底图。缓存归属按账号/题/轮次/fileRef隔离，复用前重新授权轮次。

TutorTurn 精确为 turnId/requestId/questionId/prompt/status/fileRef/answerMarkdown/failureCode/failureMessage/createdAt/updatedAt。状态 WAITING_ANSWER/GENERATING/SUCCEEDED/FAILED。WAITING可已绑定fileRef，pending只返回未绑定的等待轮；FAILED可无fileRef（取消）。不能把上传200限定为SUCCEEDED，也不能把网络超时当模型未执行；读取原轮状态确认。

所有请求仅Bearer，不轮询、不自动刷新会话、不自动重试模型。上传及retry读取超时150秒；调用方在每个await后检查原token/账号/题号/组件存活，真实401按已有条件清理会话。

## Tablet 公开控制器

沿用 `features/canvas/TabletCanvasWorkspace` 的稳定 `TabletCanvasController` 实例：

```ts
freezeAtPenUp(questionId: string, turnId: string): Promise<AuthResult<FrozenTutorAnswer>>
sendFrozenTutorAnswer(frozen: FrozenTutorAnswer): Promise<AuthResult<TutorTurn>>
```

`FrozenTutorAnswer = {questionId,turnId,upload:{manifestJson,files:[{partName,filePath}]}}`。freeze入口在真实抬笔后同步捕获，无在途笔迹时当场捕获，冻结后可继续书写；普通保存与求助串行发送。冻结 manifest 固定 saveRequestId=turnId、baseFileRef=null，包含完整题面/全部笔迹/负坐标与屏外范围。重复同轮冻结返回原包；重复上传不重新导出、不改JSON原始字节。冻结上传不调用M2保存、不修改当前作答引用。

`TabletTutorBridge` 负责前台当前题轮询/start/stop/dispose/状态接线。Bridge不复制模型/API，不写Canvas/Workspace/common；每个新轮调用冻结入口，模型retry只调用TutorApi.retryTurn。冻结等待中取消/切题/退出由控制器返回明确失败，旧异步结果不得进入新页面。

`controller.cancelTutorFreeze(questionId,turnId): void` 仅取消匹配轮次的本机等待/导出/待发送，供 Bridge.stop/dispose 使用；不调用服务端 cancel、不删除原包、不取消用户正在写的笔。已发出的 HTTP 无法撤回，迟到回执忽略。相同轮次重新进入时只复用旧捕获/完整原包，取消的旧异步上下文不会复活。

普通保存和求助导出/上传使用同一进程内单发送槽，没有持久队列。普通保存已在发送时仍先抬笔捕获，再等发送槽；求助导出/上传不切换 Surface.readOnly，后续笔迹不会进入副本。本机目录 `filesDir/m2-canvas/{accountId}/{questionId}/tutor/{turnId}/uploads/{turnId}`，manifest最后原子发布。再次请求已知轮次只读同目录完整包；不启动扫描/后台恢复，文件损坏明确失败，不静默重导。M2 pendingUpload/baseFileRef/完成状态均不由求助流程更新。

## OCR

`OcrApi.create_default().recognize(token,imagePath): Promise<AuthResult<OcrResult>>`。imagePath须为调用方原生裁剪后得到的本机私有文件路径（支持file://），不可传网络URL；HAR读取原字节并校验PNG/JPEG签名、16MiB/16M像素及完整解码，再以单个image part上传。成功只返回bodyMarkdown（最多20000字符），由Phone用户校对后另走M1 confirm。HAR不创建题目、不持久保存源图、不自动重试或删除裁剪材料。
