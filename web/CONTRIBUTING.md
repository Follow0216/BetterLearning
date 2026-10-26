# BetterLearning Web 协作约定

本文件是 `web/` monorepo 的唯一协作规范，适用于 `apps/webui` 与 `apps/admin`。
子项目内不再保留独立的 `CONTRIBUTING.md`；如与根仓库其他工程的约定冲突，以本文件为准。

## 1. 项目概览

- `web/` 是 pnpm workspace 单仓库，根目录只放编排与共享配置。
- `apps/webui`：C 端用户界面，重视 SEO、SSR/SSG、首屏性能与可访问性，端口 3000。
- `apps/admin`：B 端管理后台，重视登录态、权限、数据请求边界，端口 3001。
- `packages/`：预留的共享库目录，当前尚未创建。应用之间不得互相 `import`。

## 2. 目录结构

```text
web/
├── pnpm-workspace.yaml      # workspace 范围、catalog 版本、allowBuilds
├── pnpm-lock.yaml           # 唯一锁文件，必须提交
├── package.json             # 根编排脚本与 Prettier
├── .prettierrc.json
├── .prettierignore
├── .nvmrc
├── CONTRIBUTING.md
└── apps/
    ├── webui/
    │   ├── src/app/         # 路由、布局、metadata
    │   ├── src/components/  # 跨业务域通用组件
    │   ├── src/features/    # 按业务域组织
    │   ├── src/lib/api/     # 取数封装
    │   ├── src/assets/      # 参与构建的图片、字体
    │   └── public/          # 原样直出的静态文件
    └── admin/
        ├── src/app/         # (auth)、(dashboard)、api
        ├── src/features/
        ├── src/lib/{api,auth,rbac}/
        ├── src/config/      # 权限、导航等配置
        └── proxy.ts         # Next 16 请求拦截层（原 middleware）
```

业务域目录使用 kebab-case：`auth`、`user`、`question-content`、`personal-library`、
`ai-orchestration`、`render-image`。代码标识符仍使用英文。

## 3. 技术栈与依赖

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Node.js | `>=22.13.0` | pnpm 11 的下限；`.nvmrc` 为 24.15.0 |
| pnpm | 11.8.0 | 由根 `packageManager` 固定 |
| Next.js | 16.4.2 | App Router、Turbopack 默认；无 LTS 概念，当前 latest stable |
| React | 19.3.0 | 由 catalog 固定 |
| TypeScript | 5.9.3（`^5`） | `strict: true`；不升级到 7.x |
| Tailwind CSS | 4.3.3（`^4`） | 通过 `@tailwindcss/turbopack` 接入，无 postcss 配置 |
| ESLint | 9.39.5（`^9`） | flat config + `eslint-config-next` 16.4.2 |
| Prettier | 3.9.10 | 根级统一，含 Tailwind 类名排序插件 |
| TanStack Query | 5.104.5 | 仅 admin 预装，尚未接入 Provider |
| Zod | 4.6.7 | 仅 admin 预装 |

版本统一维护在 `web/pnpm-workspace.yaml` 的 `catalog` 中，子包使用 `catalog:` 引用。
升级 Next.js 或 React 时必须同时检查 `next.config.ts`、ESLint 规则与管理员鉴权实现。

## 4. 常用命令

所有命令默认在 `web/` 执行。

| 目的 | 命令 |
| --- | --- |
| 安装全部依赖 | `pnpm install` |
| 同时启动两端 | `pnpm dev` |
| 仅启动 C 端 | `pnpm dev:webui` |
| 仅启动 B 端 | `pnpm dev:admin` |
| 生产构建 | `pnpm build` |
| Lint | `pnpm lint` |
| 类型检查 | `pnpm typecheck` |
| 格式化 | `pnpm format` |
| 格式检查 | `pnpm format:check` |

子目录等价启动方式：

```bash
cd apps/webui && pnpm dev   # http://localhost:3000
cd apps/admin && pnpm dev   # http://localhost:3001
```

新增依赖必须使用 filter，禁止在子目录用 npm/yarn：

```bash
pnpm --filter webui add <package>
pnpm --filter admin add -D <package>
```

`pnpm typecheck` 内部先执行 `next typegen`，再执行 `tsc --noEmit`。不要改成裸 `tsc`，
否则 Next 16 的路由类型（如 `LayoutProps`）可能缺失。

### 测试现状

当前未配置单元测试运行器，**不存在可用的 `pnpm test` 脚本**。质量门禁为：

```bash
pnpm lint && pnpm typecheck && pnpm build
```

新增测试时遵循以下约定：

- 在对应子包内引入 Vitest + `@testing-library/react`，并添加 `test` / `test:run` 脚本。
- 根脚本使用 `pnpm -r test` 聚合，不重复编写具体命令。
- 测试文件命名为 `*.test.ts` / `*.test.tsx`，与被测代码同域放置。
- 单元测试不得连接真实后端；接口使用 MSW 或显式 mock。
- 优先覆盖纯函数、数据转换、权限判断与错误归一化。

## 5. 代码风格与工程约定

### 5.1 格式与 Lint

- Prettier：2 空格、双引号、分号、尾逗号、`printWidth: 100`、Tailwind 类名排序。
- ESLint：`eslint-config-next/core-web-vitals` + `typescript`，负责正确性与最佳实践。
- 不用 ESLint 承担格式职责，不使用 `eslint-plugin-prettier`。
- 提交前必须通过 `pnpm format:check`、`pnpm lint`、`pnpm typecheck`。

### 5.2 命名

- React 组件、类型、枚举：`PascalCase`。
- 函数、变量、hooks：`camelCase`；hooks 以 `use` 开头。
- 目录：`kebab-case`；Next 路由文件使用框架约定（`page.tsx`、`layout.tsx`、`route.ts`）。
- 环境变量：`UPPER_SNAKE_CASE`；仅浏览器可见的变量才使用 `NEXT_PUBLIC_` 前缀。

### 5.3 导入与边界

- 应用内使用 `@/*` 指向 `src/*`，避免 `../../../`。
- 应用之间禁止相对路径互相引用；共享能力放到 `packages/`，以 `workspace:*` 依赖。
- `apps/admin` 的接口层不得 `import` React；视图层不得直接拼接接口 URL。
- 服务端专用模块加 `import "server-only"`，防止被打进客户端 bundle。

### 5.4 Server / Client Components

- 默认使用 Server Components；只有需要状态、事件、浏览器 API 时才加 `"use client"`。
- `"use client"` 尽量下沉到叶子组件，避免整页客户端化。
- 数据获取优先在 Server Component 或 Route Handler 中完成。
- 新增页面时同时考虑 `loading.tsx`、`error.tsx`、`not-found.tsx` 与 metadata。

### 5.5 样式

- 优先 Tailwind 工具类；复杂局部样式使用 CSS Modules。
- 不引入运行时 CSS-in-JS；确需引入时必须评估 RSC 兼容性与包体积。
- 设计 token 通过 CSS 变量维护，不在组件中散落魔法颜色值。

### 5.6 请求与鉴权（admin）

- 统一走 `src/lib/api`，返回归一化的 `ApiError`（status、code、message、traceId）。
- 登录态使用 httpOnly + Secure + SameSite Cookie，禁止把 token 存入 localStorage。
- `proxy.ts` 只做粗粒度跳转，权限真相在 layout、Server Action、Route Handler 中校验。
- 越权必须返回 403 或受控错误页，不能只隐藏按钮。
- 角色不写进 URL；权限映射集中在 `src/config/permissions.ts`。

## 6. 依赖与版本管理

- 根目录执行一次 `pnpm install` 即可安装全部应用依赖。
- 仅保留根 `pnpm-lock.yaml`；禁止在 `apps/*` 下生成独立锁文件或 `pnpm-workspace.yaml`。
- 共享依赖版本写入 catalog；只影响单个应用的依赖直接写在该应用 `package.json`。
- pnpm 默认阻止依赖构建脚本。安装时若出现 `Ignored build scripts`，评估后在根
  `pnpm-workspace.yaml` 的 `allowBuilds` 中显式放行，不要直接全局放开。
- 不提交 `node_modules/`、`.next/`、`*.tsbuildinfo`、`.env*.local`。

## 7. 提交规范

采用 Conventional Commits，描述使用中文：

```text
<type>(<scope>): <简述>

type:  feat | fix | refactor | docs | style | test | chore | build | ci
scope: webui | admin | web | deps
```

示例：

```text
feat(webui): 新增课程详情页与 metadata
fix(admin): 修复无权限时未返回 403 的问题
chore(web): 统一 Next.js 依赖版本
```

提交前检查：

1. `pnpm lint && pnpm typecheck && pnpm build` 全部通过。
2. 未提交 `.next`、`node_modules`、本地环境变量与签名文件。
3. 依赖变更同时更新根锁文件。
4. 不夹带 `client/`、`server/` 中与本任务无关的改动。

## 8. 常见注意事项

- **端口**：webui 3000、admin 3001。Next 在端口被占用时会自动递增，启动后必须用
  `lsof -nP -iTCP:3000 -iTCP:3001 -sTCP:LISTEN` 确认实际端口。
- **Next 16 变更**：`next lint` 已移除，Lint 使用 `eslint .`；`middleware.ts` 更名为
  `proxy.ts`；`images.domains` 已移除，远程图片使用 `images.remotePatterns`。
- **缓存语义**：两个应用默认开启 Cache Components 与 Partial Prefetching。新增动态数据页时
  明确缓存边界，不要用客户端请求掩盖服务端缓存问题。
- **字体**：不要使用 `next/font/google`，构建期访问 Google Fonts 在国内网络下可能失败；
  使用 `next/font/local` 或系统字体栈。
- **静态资源**：需要优化的图片放 `src/assets` 并通过 `next/image` 引入；`public/` 只放 favicon、
  校验文件等原样直出资源。
- **安全**：服务端密钥只放在服务端环境变量中，禁止加 `NEXT_PUBLIC_` 前缀；日志不得输出
  token、cookie 或个人信息。
- **范围**：本文件只约束 `web/`。修改 `client/`（HarmonyOS）或 `server/`（Spring Boot）前，
  先确认各自的协作约定与当前分支状态。