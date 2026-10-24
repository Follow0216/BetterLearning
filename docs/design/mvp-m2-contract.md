# M2 HTTP／文件契约草案

状态：题集字段已固定并独立交付；作答 envelope／坐标／题面底图按根会话与 C2 方案固定为下述 v1。开发树未构建或测试。基线 develop：`063a35493be9636a976928fe63ca6b4231d75898`。

M2 包含题集创建／改名／单题移动、多层题集、Tablet 可恢复的最新笔迹与完整导出、云端当前作答保存、完成／继续状态、Phone/Web 只读完整解答。无题集删除／移动、正文编辑、历史版本、归档、搜索筛选、OCR 或 AI。沿用 M1 的 Bearer 登录、真实 principal.accountId、字符串数据库 ID、`inbox`、科目枚举与错误对象。M2 请求使用独立 DTO，继续拒绝未知字段，不扩展 M1 confirm 请求。题集部分已获根会话放行实施，作答部分按本版字段继续实施。

## 1. 题集与题目归属

`GET /api/v1/learning/collections` 保留 `{items:[...]}`，每项新增 `parentCollectionId`（根级为 null）。它返回当前账号的平面树节点；客户端按父 ID 组树，不把科目或“全部题目”造为数据库题集。待整理固定第一项：

```json
{"collectionId":"inbox","name":"待整理","system":true,"questionCount":2,"parentCollectionId":null}
```

- 真实题集支持多层创建。父题集必须是当前账号的真实题集；`inbox` 不能改名、不能作为父级。父级仅在创建时指定，M2 不提供移动题集或更改父级接口，因此无需泛化树移动／环检测流程。
- `questionCount` 为直接归属本节点的题数，不汇总子孙；M1 `GET /questions?collectionId=...` 同样只查该节点。省略 collectionId 仍为当前账号全部题目。
- 名称去首尾空白后 1–64 个 UTF-16 code units；不另做大小写、Unicode 或路径规范化。同账号同父级名称精确相同则 409；不同层级或账号可重名。真实根级名称“待整理”保留，避免与系统项混淆。
- 所有节点 ID／父 ID／目标 ID 在 SQL 中都限定当前账号；其他账号与不存在节点统一 404。

| 操作 | HTTP | 请求与结果 |
| --- | --- | --- |
| 创建题集 | POST `/api/v1/learning/collections` | `{"name":"微积分","parentCollectionId":null}` → 201，返回新节点；创建子集时父 ID 如 `"12"` |
| 改名 | PATCH `/api/v1/learning/collections/{collectionId}` | `{"name":"导数与微分"}` → 200，返回节点；不接受 parentCollectionId |
| 单题移动／移回待整理 | PATCH `/api/v1/learning/questions/{questionId}/collection` | `{"collectionId":"12"}` 或 `{"collectionId":"inbox"}` → 200，返回更新后既有 QuestionVO |

同一题移动到当前题集及重复改为当前名称均为无副作用成功；创建不另建幂等框架，重复同父同名创建返回明确 409，客户端刷新列表确认，不自动加后缀或偷偷重建。

## 2. 当前作答与状态

每题云端只有一个当前作答。新增：

| 操作 | HTTP | 说明 |
| --- | --- | --- |
| 读取当前作答 | GET `/api/v1/learning/questions/{questionId}/answer` | 200；含完整 JSON、题干快照及全部 PNG 块描述 |
| 保存完整作答 | POST `/api/v1/learning/questions/{questionId}/answer` | multipart；只有全部文件保存且数据库提交成功才返回 200/fileRef |
| 标记完成／继续 | PATCH `/api/v1/learning/questions/{questionId}/answer-state` | 200；针对当前 fileRef 原子切换状态 |
| 读取题面底图 | GET `/api/v1/learning/questions/{questionId}/answer/files/{fileRef}/question-blocks/{index}` | 与合成块相同鉴权，200 image/png，仅用于恢复可编辑画布 |
| 读取单块 | GET `/api/v1/learning/questions/{questionId}/answer/files/{fileRef}/blocks/{index}` | Bearer + 账号／题号／当前 fileRef 校验，200 image/png |

状态为 `NOT_STARTED`、`IN_PROGRESS`、`COMPLETED`。未保存过时 GET 返回：

```json
{"questionId":"1","state":"NOT_STARTED","fileRef":null,"savedAt":null,"completedAt":null,"manifest":null}
```

首次完整保存后为 IN_PROGRESS。保存结果至少返回：

```json
{"questionId":"1","state":"IN_PROGRESS","fileRef":"4e262faa-937a-4db5-8f37-9805a6c2213a","savedAt":"2026-10-24T13:00:00Z","completedAt":null}
```

M1 题目列表项和详情将新增 `answerState` 字段（同三个值），供各端显示当前状态；M1 正文、摘要和 ID 字段不变，不引入状态筛选。

完成请求：`{"state":"COMPLETED","fileRef":"4e262faa-937a-4db5-8f37-9805a6c2213a"}`。只有该题已有完整保存，且 fileRef 正是当前引用，才允许完成；重复完成保留原 completedAt。Tablet 必须先把最新本机草稿完整导出上传，收到对应成功结果后再发完成请求，不能把“本机已写入”当云端已保存。

继续请求使用相同结构、`state="IN_PROGRESS"`，保留当前文档/fileRef/savedAt，清除 completedAt；重复继续为无副作用成功。已完成题保存新内容前必须先继续，防止迟到的保存请求自动取消完成。请求中的旧 fileRef 一律 409，未有完整保存即完成为 409；M2 不提供“撤销到未开始”或删除作答。

## 3. multipart 与完整文档（供 C2 对齐）

固定 JSON part 名为 `manifest`，媒体类型为 `application/json`。PNG 使用各自唯一 part 名，必须与 `blocks[].partName` **逐字一致**；如 `chunk_0000`、`chunk_0001`。不按上传顺序或 filename 猜配对，不接收重复 part、缺块或未声明的额外文件；`manifest` 为保留名。JSON part 可以带 filename，但仍不是 PNG 块。

拟定请求 envelope：

```json
{
  "schemaVersion":1,
  "saveRequestId":"b82c1d44-d75a-4ec6-b653-023f1ed921b2",
  "baseFileRef":null,
  "canvas":{"width":1200,"height":2400,"originX":-200,"originY":-100,"coordinateUnit":"logical"},
  "document":{"schemaVersion":1,"questionLayout":{"questionBodySha256":"<64位小写hex>","contentWidth":1200,"fontSize":16,"rendererId":"local-markdown-v1","bounds":{"x":0,"y":0,"width":1200,"height":1200},"blocks":[{"blockId":"body-0","x":0,"y":0,"width":1200,"height":1200}],"rasterBlocks":[{"index":0,"partName":"question_0000","x":0,"y":0,"width":1024,"height":1024,"pixelWidth":1024,"pixelHeight":1024,"sha256":"<64位小写hex>"}]},"strokes":[{"strokeId":"stroke-1","color":"#152744","width":2,"points":[{"x":-20,"y":40},{"x":60,"y":80}]}]},
  "blocks":[
    {"index":0,"partName":"chunk_0000","x":0,"y":0,"width":1024,"height":1024,"pixelWidth":1024,"pixelHeight":1024,"sha256":"<64位小写hex>"},
    {"index":1,"partName":"chunk_0001","x":1024,"y":0,"width":176,"height":1024,"pixelWidth":176,"pixelHeight":1024,"sha256":"<64位小写hex>"}
  ]
}
```

示例仅列出部分块与占位 hash，实际上传必须按下述整格规则给齐两组 PNG。`document.schemaVersion=1` 固定：questionLayout 与 strokes 为必填；questionLayout 精确使用示例字段，bounds 是有符号世界矩形，blocks 是题面逻辑块（blockId 唯一），rasterBlocks 是恢复擦写所需的干净题面底图。strokes 每笔仅含 strokeId/color/width/points；points 为 `{x,y}`，strokeId 唯一，color 为 #RRGGBB 或 #AARRGGBB，笔宽 0.5–64，最多 20,000 笔／合计 200,000 点，坐标绝对值≤1e6。rendererId 为非空≤64字符的本地渲染器标识，contentWidth/fontSize 为有限正数。

- canvas.originX/originY 是完整导出范围的有符号世界原点；合成 blocks 的 x/y 相对此原点非负。document 的笔迹／逻辑题面／rasterBlocks 继续使用有符号世界坐标，不丢负坐标内容；pixelWidth/pixelHeight 是实际 PNG 像素尺寸；渲染器按描述放置，不能把逻辑尺寸当像素尺寸或裁切为当前屏幕宽度。
- index 从 0 连续，展示顺序以 index 为准。合成块覆盖完整相对范围 `[0,canvas.width]×[0,canvas.height]`，不漏首尾、最右端或中间区间。固定每格 1024 逻辑单位、行优先、无重叠：columns=ceil(width/1024)，index i 的 x=(i%columns)*1024、y=floor(i/columns)*1024，边缘宽高取剩余值。pixelsPerUnit 固定 1，不另传字段，pixelWidth/Height 等于 width/height；canvas.width/height/originX/originY 为整数。
- 合成 blocks PNG 是**题干与笔迹合成后的完整解答块**，不是只有透明笔迹。服务端从当前账号数据库复制 questionId、subject、bodyMarkdown 到最终 manifest 的 `questionSnapshot`，并核对 questionBodySha256 为数据库正文原始 UTF-8 的 SHA-256；不把客户端传来的其他题干当本题保存。这样云端同时保留可读原题干、可编辑笔迹 JSON 与完整合成结果。
- GET 当前作答在同一 envelope 上附加 `questionSnapshot`，合成 blocks 及 document.questionLayout.rasterBlocks 每块附加相对 API `url`，返回 `manifest`；不返回文件系统路径。Phone/Web 按 index、逻辑尺寸展示所有合成 PNG，忽略编辑数据；Tablet 可读取同一 document 恢复继续作答。Web 经既有 HttpOnly 会话 BFF 添加 Bearer 并使用 no-store 转发 manifest/PNG，不用匿名 img URL 直连受保护目录。
- 本机最新草稿由 C2 按账号＋questionId 隔离保存；云端失败不能删除本机草稿、标完成或用旧图伪报成功。登录切换不能把上一账号的草稿、fileRef 或 PNG 带到下一账号。

拟定边界（对齐时结合真实导出确定，不裁掉超限部分）：manifest ≤8 MiB，单 PNG ≤16 MiB，两组 PNG 合计 ≤64 MiB，合计最多 256 块；每块宽高正数且各不超过 16384 像素、单块≤16M像素、合计≤64M像素。合成块相对坐标须有限且落在 canvas 范围内，世界坐标（含半笔宽外延）不得超出 origin+canvas 完整范围，负世界坐标不得被裁掉；PNG 签名、可解码性、实际尺寸和 SHA-256 均须匹配。超限整体拒绝，不能部分保存后返回 fileRef。Spring multipart 上限须高于协议有效载荷并计入边界开销，例如 16MB 单 part／80MB 整请求；实际值在最终契约与配置中统一。

题面底图 `rasterBlocks` 使用同一整格规则，但格原点为 `floor(questionLayout.bounds.x/y)`，宽高为向外取整后的题面范围，块 x/y 保留该世界原点；partName 为 `question_0000` 等，合成块为 `chunk_0000` 等。两组 index 各从0连续、partName 全包唯一，底图也必须给齐，不接受只有合成图。底图 GET 使用 question-blocks 路由，旧 fileRef 与跨账号均不可读。

## 4. 保存、覆盖、失败与重试

草案选择 **当前 fileRef 条件覆盖**，不新增历史版本或通用幂等账本：

1. 客户端在一次云端保存开始时冻结 saveRequestId（小写标准 UUID）、baseFileRef、manifest 的 UTF-8 字节和所有 PNG；网络重试使用同一冻结包。首次保存 baseFileRef 必须为 null；后续使用最近读取／保存成功的当前 fileRef。
2. 服务端校验账号／题号，流式写同一持久化根目录的临时目录，校验所有 JSON／PNG；完成全部写入并 force 后，以同文件系统原子 rename 发布一个不可变目录。fileRef 是服务端生成的随机 UUID，与客户端路径或 filename 无关。
3. 数据库事务锁定本题／当前作答。若当前 saveRequestId 与本次相同：请求摘要相同则返回原 fileRef 及当前状态（若已 COMPLETED 仍返回 COMPLETED，不降回 IN_PROGRESS），不同则 409。否则要求 baseFileRef 等于当前 fileRef；无当前记录时仅接受 null。匹配且当前不是 COMPLETED 才原子替换当前指针，状态为 IN_PROGRESS。
4. 请求摘要为原始 manifest UTF-8 字节的 SHA-256；manifest 已包含两组每块摘要，且服务端逐一核对实际 PNG，因此摘要绑定全部文件内容。客户端不得在重试时重新排 JSON 字段、改空白或重新导出图片。事务经 Spring 代理实际提交后才构造成功响应；未提交不能向 HTTP 暴露 fileRef。
5. 同一 base 的并发不同保存只有一个可提交，另一个 409，原最新解答不被旧设备覆盖。A 保存→B 覆盖→A 的迟到原包重试，因原 base 与 B 不匹配而 409，不重新覆盖 B。去重只承诺当前保存及原包重试，不承诺查询过去 saveRequestId，也不声称能在服务端拒绝全历史 ID 复用；新动作必须生成新 ID 是客户端契约。
6. 409 后保留本机草稿，明确提示刷新当前云端作答；不做冲突选择界面，不静默把 base 改为新 fileRef 并自动覆盖。超时／5xx 后先按冻结包重试；若前次实际提交但响应丢失，当前同 requestId＋摘要会返回原成功结果。

任一校验、写盘、rename 或 DB 提交失败均无成功 fileRef，旧的当前指针保持不变；清理本次临时／未安装候选目录。重复请求返回原成功结果时，也须清理本次多余候选，不能误删原当前目录。文件系统与数据库不能组成真正的分布式原子事务；“先完整文件、后 DB 指针”可避免可见半包，崩溃窗口可能留下不可见孤儿。MVP 不做启动扫描、定时孤儿回收或通用恢复；崩溃孤儿保留为已知空间限制，不对外可见。本次明确不实现后台清理框架。

覆盖后的旧 fileRef 不作为历史资源对外读取，统一 404。每个块 GET 必须先按 principal.accountId、questionId、**当前 fileRef** 查库授权，再读取服务端生成路径；仅知道 UUID 不获得访问权。新保存导致旧块 404 时，查看器重新读取最新 manifest，不能混用两份解答的块。

## 5. 最小持久化与配置

为便于独立接收，题集与作答分提交：V3 先扩题集树；待 C2 schema 对齐后由 V4 增当前作答。V1/V2 不改字节：

- `question_collection` 增加可空 `parent_collection_id`，根为 null，同账号复合自引用 FK；根／非根各用明确唯一索引保证同级同名冲突。无 parent 更新 API。
- `question` 增加 `UNIQUE(account_id,question_id)`，供当前作答复合外键使用；沿用已有题集归属字段与复合 FK。
- 新增 `question_answer`，每题一行：`account_id, question_id`（复合主键/外键）、`file_ref`（唯一 UUID）、`save_request_id`、`request_hash`、`state`（IN_PROGRESS/COMPLETED）、`saved_at`、`completed_at`。未保存题没有该行。无版本号、历史表、归档、租约或额外文件目录表。
- 完整 manifest（包含服务端题干快照和 C2 document）及 PNG 存在 `APP_FILES_ROOT` 下的不可变对象目录，DB 只存当前引用与状态。manifest 不缺笔迹 JSON，PNG 不缺题干／笔迹合成块。
- `APP_FILES_ROOT` 为必须显式配置的绝对持久化目录，不放 target、jar 内或临时目录；应用启动校验目录可用，staging 与 objects 同文件系统。不发布静态资源映射，不把真实路径写入响应或日志。

使用现有 Spring WebMVC multipart、Jackson、MyBatis、PostgreSQL 与 JDK Files/FileChannel/MessageDigest/ImageIO；不改现有依赖坐标、版本、scope 或插件，不新增图片/存储框架。文件实现位于 learning 的小型存储服务，配置绑定由 app 注册；HTTP 适配仍在 app 使用已认证 principal。

## 6. 错误与验收

错误对象仍是 `{code,message,traceId,fieldErrors?}`。原 401/403/404/500/503 语义保持。新增场景拟定：同级名称重复、base/fileRef 过期、未完整保存即完成、已完成时再次保存 → 明确 409 错误码（A0450 名称重复；A0451 当前引用已改变；A0452 尚未完整保存；A0453 已完成须先继续；A0454 同 saveRequestId 的包内容不同）；非法 part/坐标/摘要/PNG 为 400 A0400，超大小 413 A0430，媒体类型错误 415（沿用 A0428），文件系统不可用 503。在实现中统一登记 ErrorCode、OpenAPI 与客户端映射，禁止把文件错误当空解答。

C4 最小验收：题集主链路／多层归属与账号隔离；完整长文档所有块和最右端；缺块保存失败不返回 fileRef且保留旧解答／本机草稿；基本原包重试；保存后完成／继续状态；进程重启后当前 JSON/PNG 可读；Tablet 本机笔迹恢复与继续、Phone/Web 完整只读查看。输入边界沿实现做必要覆盖，不扩展为穷举大型故障矩阵。开发树不执行这些验证。

## 7. 当前代码映射与实施落点

| 当前 owning source（相对仓库根） | 已有事实／M2 落点 |
| --- | --- |
| `server/app/src/main/resources/db/migration/V2__create_question_library.sql` | 当前只有平级题集与题干，V3 增树父级，V4 再增当前作答 |
| `server/app/src/main/java/com/betterlearning/server/learning/controller/LearningController.java` | M1 四操作；新增题集写／完整作答／状态／文件 HTTP 适配 |
| `server/learning/src/main/java/com/betterlearning/server/learning/service/{LearningService.java,impl/LearningServiceImpl.java}` | 当前仅确认／详情／分页／题集 GET；新增题集写与当前作答编排，不塞 app 业务逻辑 |
| `server/learning/src/main/java/com/betterlearning/server/learning/{dao/LearningDao.java,mapper/}` 与 `src/main/resources/mapper/learning/` | 沿用账号条件与固定 SQL，新增树写／单题移动／当前作答行锁与 CAS |
| `server/learning/src/main/java/com/betterlearning/server/learning/model/` | 题集新增父 ID、题目新增 answerState；增加有界 manifest/answer DTO/VO及存储服务 |
| `server/app/src/main/java/com/betterlearning/server/config/AppPropertiesConfiguration.java`、`src/main/resources/application.properties`、根 `.env.example` | 新增文件根目录绑定／校验及一致的 multipart 限制，不改鉴权链 |
| `server/app/src/main/java/com/betterlearning/server/exception/GlobalExceptionHandler.java`、`server/common/src/main/java/com/betterlearning/server/enums/ErrorCode.java` | 补齐 multipart/413/存储错误映射，保留既有 DB 故障窄判定 |
| `server/app/src/test/java/com/betterlearning/server/learning/`、`.../support/TestData.java`、OpenAPI 契约测试 | 新增完整保存／状态／文件故障测试；适配新 FK 清理与精确新增路径断言，由 C4 执行 |
| `client/common/src/main/ets/features/learning/{LearningModels.ets,LearningApi.ets}` | M1 仅题干与平级题集；C2 拥有新增公共模型／网络／multipart与可编辑文档 schema |
| `client/apps/tablet/src/main/ets/components/LibraryPane.ets` | develop 当前只有题干只读，没有可复用的正式画布导出；C2／Tablet 会话实现笔迹与完整导出 |
| `web/apps/webui/src/lib/api/library-contract.ts`、`src/features/library/` | Web 现仅 M1 只读；由 Web 会话接题集写及完整解答只读，不直接依赖服务端实现类 |

## 8. 固定与实施顺序

题集代码独立提交 `fdc5318b73a98819bf619d2b73fed25759408df1`，可先接收。作答按本版字段独立提交；C2 拥有公共 HAR／画布模型，C1 不写 client/web。服务端验证固定整格、负世界范围与两组 PNG，C4 用最小主链路与基本状态门禁验证，不做大型恢复框架。字段/schemaVersion 变更必须由 C1/C2 同步此文档，不能客户端自行换 part 名或坐标基准。
