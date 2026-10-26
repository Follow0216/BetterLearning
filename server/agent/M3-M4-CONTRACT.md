# M3/M4 辅导与 OCR 接口契约 v1

接口行为以当前服务端与客户端实现为准。真实模型、设备与端到端行为仍需独立验证。

## HTTP 接口

统一 Bearer、当前 principal.accountId、既有错误对象、账号隔离。questionId 沿 M1 字符串 ID；turnId/requestId 为 UUID 字符串，客户端不得提交 accountId。

| 操作 | 路径 | 输入/输出 |
| --- | --- | --- |
| 发问 | POST /api/v1/learning/questions/{questionId}/tutor-turns | {requestId,prompt} → 200 Turn（创建/重复统一200） |
| 同题轮次 | GET 同上 | {items:[Turn]}，最近50轮，createdAt/turnId正序 |
| 轮次详情 | GET 同上/{turnId} | Turn |
| Tablet前台轮询 | GET 同上/pending | {turn:Turn或null}，只返回该题 WAITING_ANSWER且fileRef=null |
| 冻结上传 | POST 同上/{turnId}/answer | 与M2相同manifest+两组完整PNG；saveRequestId=turnId，baseFileRef=null；独立存储，不调用M2保存 |
| 原轮模型重试 | POST 同上/{turnId}/retry | 空JSON；仅FAILED且已有fileRef可重试，复用已接受文件 |
| 放弃等待 | POST 同上/{turnId}/cancel | 空JSON；仅WAITING_ANSWER，标FAILED/TABLET_CANCELLED |
| 冻结文件 | GET 同上/{turnId}/answer 与 /answer/files/{fileRef}/{blocks或question-blocks}/{index} | 沿M2 ReadManifest；fileRef按该轮授权，不能按当前作答授权 |
| OCR | POST /api/v1/learning/ocr | multipart单part image；PNG/JPEG，<=16MiB，返回 {bodyMarkdown} |

Turn字段：turnId,requestId,questionId,prompt,status,fileRef,answerMarkdown,failureCode,failureMessage,createdAt,updatedAt。
状态只有 WAITING_ANSWER / GENERATING / SUCCEEDED / FAILED。回答/failure互斥；等待平板时fileRef=null；完整冻结后绑定一次fileRef且之后不可替换，绑定后到领取模型前可短暂保留WAITING_ANSWER且不再出现在pending中。模型重试不创建新轮次、不重新冻结。新requestId始终新轮并重新请求平板。
同账号最多一个WAITING_ANSWER/GENERATING（跨题同样互斥）。账号级requestId+相同questionId/prompt先返回原轮；同requestId用于不同题/不同prompt或账号已有其他活动轮返回409。新requestId的新轮必须等该账号前轮结束；失败轮retry也必须满足同一账号级活动限制。列表/详情在Phone/Web相同。

Tablet仅前台、当前题questionId轮询。收到pending若落笔中，等待本笔结束后立即冻结题面、所有笔迹、负坐标及视口外范围；冻结完成可继续写。冻结包在本轮确认接收前保持原字节，重复上传不得重新导出。普通保存/冻结上传共用客户端顺序发送器，但服务端对象和引用完全分离。缺块/摘要不符不绑定fileRef，不调用模型；有效无笔迹仍有完整题面合成块，不当缺失快照。
上传完整接受后，调用 TutorService.generate(...,false) 同步尝试模型。重复上传若轮次已有 fileRef，不得替换文件；同包可再次调用 generate(...,false)，原子领取确保只有一次实际模型调用，GENERATING/SUCCEEDED 直接返回原轮；若响应超时，Phone/Tablet 读轮次状态确认。GENERATING 重复 retry 返回当前轮；SUCCEEDED 同样返回原结果。失败后显式 retry，调用 generate(...,true)。无自动模型重试（maxRetries=0），避免未知结果的隐式重复收费。进程在绑定 fileRef 后或 GENERATING 中止且无后续原包重试属于已知限制。

## 模块依赖与配置

`agent` 提供 `TutorModels`、`TutorService`、`TutorTurnStore`、`AgentModelException` 和 `AgentProperties`。`app` 装配 `DefaultTutorService`、`OcrService` 与 `TutorTurnStore` 适配实现，调用 learning 的独立轮次和不可变文件存储；learning 和 agent 不互相依赖，也不依赖 user DO/Mapper。
TutorTurnStore的每次状态写是短数据库事务，不能包住模型调用。createOrGet及claimGeneration有账号级行锁/活动唯一约束原子保证（retry也覆盖），不是仅按questionId加锁；complete/fail只更新原轮GENERATING，不能覆盖已完成结果。模型调用前需已完整接受文件并提交，且FrozenAnswer必须来自该轮文件，不得用M2当前fileRef。方法详见接口Javadoc。

`AgentModelException` 对外映射为安全的 503 错误对象，不透传原始模型异常；辅导失败在 Turn 内保存 failureCode/failureMessage。
AgentProperties 键：app.agent.ocr.{base-url,model,api-key,timeout-seconds} 与 app.agent.tutoring.{base-url,model,api-key,timeout-seconds}。环境映射 APP_AGENT_OCR_BASE_URL/MODEL/API_KEY/TIMEOUT_SECONDS 以及 APP_AGENT_TUTORING_BASE_URL/MODEL/API_KEY/TIMEOUT_SECONDS。timeout-seconds 默认 120。base-url 原样交 LangChain4j，保留路径前缀；不做 host 根路径重写。无配置时 M1/M2 仍可启动，真正调用明确失败，不返回模拟答案。
model与base-url不设置真实服务默认值。API key不入响应、日志/toString、仓库配置、报告；只报缺失键名。异常对外只用安全固定文案，不透传供应商原始body/header/exception message。

## M4与模型边界

Phone原生拍照/选图→矩形裁剪→临时裁剪图→OCR→用户校对Markdown→既有M1 confirm。OCR不自动入库、不建持久任务表、不持久保存源图。调用失败/响应未知保留本机校对草稿/原材料，确认成功后按既有本机归属清理，不能误删其他账号材料。
OCR读完整PNG/JPEG并实际验证格式/维度（最多16MiB/16M像素）与完整解码；不接受图片URL，不读任意本机路径。只把用户提交的图交模型，不引入新图像库。qwen3.5-ocr与tutoring分别配置，互不复用密钥。
辅导模型必须接收本轮全部合成图片和题干/提问（MVP同步调用上限：全部图像字节合计32MiB、最多256张；不截块/压缩，不在库内自动重试，图像detail=HIGH）；不能只发正文或偷偷截取前N块。超过供应商能力须明确失败并保留冻结文件供配置修正后retry。任何供应商拒绝图像都必须明确失败，禁止静默纯文本降级。具体deepseek模型需具备图像能力，不能仅凭品牌名推断。

`agent` 的 LangChain4j 直接依赖和版本见 [依赖版本记录](DEPENDENCIES.md)。实际解析与兼容性以 Maven 构建和运行检查为准；缺配置时 MODEL_NOT_CONFIGURED 明确失败。

## 验证范围

验证应覆盖：重复发问返回原轮；活动轮冲突；无平板保留等待/可取消；抬笔全量冻结后继续写；普通保存不覆盖轮次冻结；缺块不调模型；Phone/Web 同轮；失败 retry 沿原 fileRef；新轮重新冻结；账号隔离；OCR 真实图像→校对→M1 且不自动入库；base-url 含路径前缀；无配置/不支持图像明确失败。真实模型需要配置，未验证时须明确记录。
