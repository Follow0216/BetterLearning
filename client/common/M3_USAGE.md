# M3/M4 共享 HAR 接线

契约来源 C3 `db7c9f2b`，C1 冻结存储 `a97b47ba`。本提交提供真实 HTTP API、DTO 与控制器边界；画布的抬笔冻结/顺序发送实现随后提交。未挂载或未实现时控制器明确失败，不伪造成功。C2 开发树未构建/运行设备，正式验证由 C4 执行。

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

`FrozenTutorAnswer = {questionId,turnId,upload:{manifestJson,files:[{partName,filePath}]}}`。freeze入口实现未交付前明确失败。最终实现会在真实抬笔后同步捕获，冻结后可继续书写；普通保存与求助串行发送。冻结 manifest 固定 saveRequestId=turnId、baseFileRef=null，包含完整题面/全部笔迹/负坐标与屏外范围。重复同轮冻结返回原包；重复上传不重新导出、不改JSON原始字节。冻结上传不调用M2保存、不修改当前作答引用。

独立 `TabletTutorBridge` 由C3负责前台当前题轮询/start/stop/dispose/状态接线。Bridge不复制模型/API，不写Canvas/Workspace/common；每个新轮调用冻结入口，模型retry只调用TutorApi.retryTurn。冻结等待中取消/切题/退出由控制器返回明确失败，旧异步结果不得进入新页面。

## OCR

`OcrApi.create_default().recognize(token,imagePath): Promise<AuthResult<OcrResult>>`。imagePath须为调用方原生裁剪后得到的本机私有文件路径（支持file://），不可传网络URL；HAR读取原字节并校验PNG/JPEG签名、16MiB/16M像素及完整解码，再以单个image part上传。成功只返回bodyMarkdown（最多20000字符），由Phone用户校对后另走M1 confirm。HAR不创建题目、不持久保存源图、不自动重试或删除裁剪材料。
