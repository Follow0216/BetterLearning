# webui

BetterLearning C 端用户界面（Next.js App Router，端口 3000）。登录与最小注册通过
`src/app/api/auth/*` 的最小 BFF 访问后端，令牌只保存在 HttpOnly Cookie 中。

## 启动

在 `web/` 下执行：

```bash
pnpm install
pnpm dev:webui
```

或在 `web/apps/webui/` 下执行 `pnpm dev`。默认打开 <http://127.0.0.1:3000>。

## 服务端环境变量

两个变量都只供 Next 服务端使用，**禁止加 `NEXT_PUBLIC_` 前缀**，也不要写进客户端代码。

| 变量                | 说明                                                                  | 开发默认                             |
| ------------------- | --------------------------------------------------------------------- | ------------------------------------ |
| `AUTH_API_BASE_URL` | Spring Boot 后端地址，仅允许 http/https；BFF 只转发四个白名单认证路径 | `http://127.0.0.1:18081`             |
| `APP_ORIGIN`        | 浏览器访问本应用的外部 origin，用于状态变更请求的同源校验             | 未配置时按实际 Host 推导（仅非生产） |

### 开发

本任务使用的独立测试后端实际监听 **18081**，因此不配置时默认连接 18081：

```bash
AUTH_API_BASE_URL=http://127.0.0.1:18081 pnpm dev:webui
```

常规后端默认监听 **8080**，此时必须显式指定，不要沿用 18081：

```bash
AUTH_API_BASE_URL=http://127.0.0.1:8080 pnpm dev:webui
```

开发环境可以不设置 `APP_ORIGIN`，此时同源判断根据请求的直接 Host 与协议重建，
不读取任何 `X-Forwarded-*` 头。一旦显式设置，就只接受该 origin：

```bash
APP_ORIGIN=http://127.0.0.1:3000 pnpm dev:webui
# 若通过 localhost 访问，则配置 http://localhost:3000
```

### 生产

生产必须显式配置两个变量，且 `AUTH_API_BASE_URL` 没有默认值，缺失会直接报错：

```bash
NODE_ENV=production \
AUTH_API_BASE_URL=https://api.example.com \
APP_ORIGIN=https://app.example.com \
pnpm --filter webui start
```

- `APP_ORIGIN` 必须与浏览器地址栏的 origin 完全一致，只能带协议、主机和可选端口，
  不允许路径、query、hash、userinfo、通配符或子串匹配；生产环境请使用 HTTPS。
- 仅当 `NODE_ENV=production` 时会话 Cookie 才带 `Secure`，因此生产不能以 HTTP 提供服务。
- Cookie 固定为 `HttpOnly; SameSite=Lax; Path=/`，有效期不超过后端 `expiresIn`，不做滑动续期。

## 部署边界

BFF 由 Next 服务端统一访问后端，后端按 `getRemoteAddr()` 限流，所以所有浏览器用户共享
同一个来源 IP 配额（默认登录 30 次/分钟、注册 10 次/小时）。多人联调时可能提前触发 429；
需要按真实客户端限流时，应在后端调整，而不是在 BFF 伪造转发头。
