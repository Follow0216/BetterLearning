# M1 HTTP 契约

本契约供 Phone、Pad、Web 和共享网络层实现同一套真实题库流程。M1 仅新增人工确认入库、题库分页、详情、题集浏览及 dev 管理员初始化；不提供题目编辑、题集创建/改名/移动、搜索、作答、AI 或文件上传接口。

## 公共规则

- 所有下述接口使用 `Authorization: Bearer <accessToken>`，沿用 `POST /api/v1/sessions`。当前账号由现有 JWT、Redis 会话及数据库实时校验后的 principal 提供，客户端不传 `accountId` 或角色。
- 数据库 ID 全部以正十进制字符串返回，不转 JS/ArkTS number。固定虚拟题集 `collectionId="inbox"`，名称“待整理”；数据库题目 `collection_id=NULL` 对应此标识，不创建额外系统题集行。
- 科目 `subject` 是大小写敏感的稳定枚举，M1 支持 `HIGHER_MATHEMATICS`（高等数学）与 `OTHER`（其他）；必须提供，不接收中文自由文本或 demo ID。待整理不属于科目。
- 无独立标题。列表与详情的 `summary` 均由服务端从 `bodyMarkdown` 去除常见 Markdown 标记、收拢空白后取前 50 个 Unicode 码点，不追加省略号；无文本时返回“（无文本预览）”。它是文本预览，不是完整 Markdown/LaTeX 渲染器；三端直接显示服务端值，不各自计算。
- `bodyMarkdown` 原样保存，不 trim 或重写；要求非空白且最多 20,000 个 UTF-16 code units。详情返回同一原文。用户在确认前编辑，确认入库后 M1 不提供修改接口。
- 时间均为带偏移量的 ISO 8601 字符串。JSON 响应直接是下列对象，不新增 data/code 成功包装。严格 JSON 规则继续生效，未知或被忽略字段为 `400 A0427`。

## 确认入库

`POST /api/v1/learning/questions`，成功及同内容重试均返回 `201`。

```json
{
  "requestId": "74615d72-665b-4f56-a9a1-3215809de3ea",
  "subject": "HIGHER_MATHEMATICS",
  "bodyMarkdown": "# 求导\n求函数 **f(x)=x²** 的导数。"
}
```

```json
{
  "questionId": "1",
  "collectionId": "inbox",
  "subject": "HIGHER_MATHEMATICS",
  "bodyMarkdown": "# 求导\n求函数 **f(x)=x²** 的导数。",
  "summary": "求导 求函数 f(x)=x² 的导数。",
  "createdAt": "2026-10-24T10:00:00Z"
}
```

`requestId` 必须为小写标准 UUID 形式的 36 字符字符串。客户端在一次确认动作开始时生成并保存它，双击、超时/断网后的重试复用同一个 ID 及原请求体；用户重新编辑正文或学科后属于新的确认动作，生成新 ID。

数据库唯一约束保证同账号同 `requestId` 最多保存一题。已有记录的正文及科目与请求完全相同时返回原 questionId、createdAt、原文和摘要；同 ID 配不同正文或科目返回 `409 A0445`，不覆盖原记录。不同账号可使用相同 requestId，各自创建一题。去重不按正文全局匹配：同一正文的新确认动作可另存。

入库始终归入当前账号的待整理；M1 不接收 collectionId、title、summary、accountId、questionId。响应丢失后可按原 requestId 重试取得原记录。失败不返回伪成功，不要求客户端换新 requestId 绕过未知结果。

## 题库分页与按题集浏览

`GET /api/v1/learning/questions?page=1&pageSize=20`。

可选 `collectionId=inbox` 或正十进制题集 ID；缺省表示当前账号所有题目。`page` 从 1 开始，1–1,000,000；`pageSize` 缺省 20，范围 1–100。不使用 offset/pageIndex/size 等别名。非法值为 `400 A0400`。指定不存在或不属于当前账号的真实题集一律 `404 A0606`。超过末页时 items 为空，仍返回实际 total。

排序固定为 `created_at DESC, question_id DESC`，新确认记录排在最前；分页是普通页码分页，不保证跨并发写入的快照。

```json
{
  "items": [
    {
      "questionId": "1",
      "collectionId": "inbox",
      "subject": "HIGHER_MATHEMATICS",
      "summary": "求导 求函数 f(x)=x² 的导数。",
      "createdAt": "2026-10-24T10:00:00Z"
    }
  ],
  "page": 1,
  "pageSize": 20,
  "total": 1
}
```

列表不含 bodyMarkdown；详情按 questionId 加载。

## 题目详情

`GET /api/v1/learning/questions/{questionId}`，返回与确认入库相同的详情对象，状态 `200`。不存在与其他账号的题目统一 `404 A0606`，不暴露其他账号是否持有该 ID。ID 必须是范围内的正 long 十进制字符串，非法格式/溢出为 `400 A0400`。

## 题集浏览

`GET /api/v1/learning/collections`，状态 `200`。

```json
{
  "items": [
    {"collectionId": "inbox", "name": "待整理", "system": true, "questionCount": 1}
  ]
}
```

当前账号没有任何题目时也返回待整理，questionCount 为 0。待整理固定第一项；其余真实题集按 collectionId 升序，仅返回当前账号题集和题数。M1 没有题集写接口，也不导入 P0 fixture 或创建“导数与微分”等演示数据。

## 错误与登录失效

沿用现有错误对象，字段错误只在适用时出现：

```json
{"code":"A0445","message":"确认请求已被其他内容使用","traceId":"..."}
```

| 状态 / code | 含义 |
| --- | --- |
| 400 / A0400 | 请求字段、枚举、UUID、正文、ID 或分页不合法 |
| 400 / A0427 | JSON 无法解析、未知字段、被忽略字段 |
| 401 / A0200 | 未登录、JWT/会话失效；进入既有登录恢复流程 |
| 403 / A0300 | 已登录但无 ADMIN 权限（仅管理员接口） |
| 404 / A0606 | 当前账号范围内不存在题目或题集 |
| 409 / A0445 | 同账号 requestId 已用于不同正文或学科 |
| 503 / C0001 | 数据库或 Redis 不可用，不当作空题库 |
| 500 / B0001 | 未知约束、映射或内部错误，不输出 SQL、正文或秘密 |

## 管理员登录与 dev 默认账号

仅显式启用 Spring `dev` profile 时初始化管理员。默认用户名 `admin`、默认密码 `admin123`；可通过 `APP_DEV_ADMIN_USERNAME`、`APP_DEV_ADMIN_PASSWORD` 覆盖，值按字面读取，密码不插值也不 trim。`APP_DEV_ADMIN_ENABLED=false` 可在 dev 中显式禁用；非 dev 永不初始化，不设置正式环境通用账号。

已有用户名若是 USER，初始化失败并明确提示，不提权；已有 ADMIN 只校验配置密码，匹配则原样保留，不匹配则明确失败，不自动重置。更换配置不会撤销旧 ADMIN 账号或旧凭据；两个 dev 初始化器独立提交，任一失败可能留下另一初始化器已创建的账号，但不会覆盖已有角色或哈希。公共注册接口仍固定创建 USER。既有 dev 普通账号初始化及其 `SPRING_SECURITY_USER_NAME/PASSWORD` 要求保留，普通账号用户名必须与管理员不同。管理员默认密码仅适用于该 dev 初始化器，不放宽公共注册的 8 字符密码要求。

管理员沿用 `POST /api/v1/sessions`：

```json
{"username":"admin","password":"admin123"}
```

成功响应沿用既有 `SessionVO`：`accessToken`、`tokenType="Bearer"`、`expiresIn`、`user`；其中 `user.role` 必须为 `ADMIN`。后台用令牌再请求 `GET /api/v1/admin/session`，成功 `200` 返回既有 `UserVO`（accountId、username、phone、email、role、createdAt、updatedAt）。普通 USER 调此接口为 `403 A0300`；前端不能仅检查本地 fixture 或伪造 role 进入管理员会话。沿用现有退出接口，不新增第二套登录/JWT/RBAC。
