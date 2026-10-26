# Repository Guidelines

## 项目结构与模块边界

- `client/`：HarmonyOS ArkTS/ArkUI 工程；`apps/phone`、`apps/tablet` 为两个入口模块，`common/` 为共享 HAR。源码位于各模块 `src/main/ets/`，资源位于 `src/main/resources/`，应用级资源位于 `AppScope/`。
- `server/`：Java 17、Spring Boot 单应用、多模块 Maven 工程；`common` 保存公共契约，`user` 实现用户与认证，`learning`、`agent` 预留业务边界，`app` 负责启动与装配且是唯一可执行 jar。源码位于各模块 `src/main/java/`，应用配置与迁移位于 `app/src/main/resources/`，跨模块测试位于 `app/src/test/java/`。
- `web/`：pnpm workspace；`apps/webui` 为用户端，`apps/admin` 为管理端。路由位于各应用 `src/app/`，静态资源位于 `public/`；`packages/` 尚未创建。


## 构建、测试与本地开发

在表中指定目录执行命令。Web 使用 `.nvmrc` 指定的 Node.js 和 `packageManager` 固定的 pnpm；服务端使用 JDK 17 与仓库 Maven Wrapper。

| 目录 | 命令 | 用途 |
| --- | --- | --- |
| `web/` | `pnpm install` | 安装 workspace 依赖 |
| `web/` | `pnpm dev` | 同时启动 webui（3000）与 admin（3001） |
| `web/` | `pnpm dev:webui` / `pnpm dev:admin` | 单独启动应用 |
| `web/` | `pnpm build` | 构建两个应用 |
| `web/` | `pnpm lint` / `pnpm typecheck` | ESLint 与路由类型、TypeScript 检查 |
| `web/` | `pnpm format:check` / `pnpm format` | 检查或修复格式 |
| 仓库根目录 | `./start-server.sh` / `./start-server.sh --check` | 加载环境、打包并启动 app／仅检查环境 |
| `server/` | `./mvnw test` / `./mvnw package` | 运行测试／测试并打包 |

客户端使用 DevEco Studio 打开 `client/`，同步依赖后分别构建、运行 phone 与 tablet；仓库没有 `hvigorw` 脚本。

## 代码风格与命名

- Web 使用 2 空格、双引号、分号及 Prettier；保持 TypeScript strict，使用 `@/*` 导入。组件与类型采用 `PascalCase`，函数与变量采用 `camelCase`，业务目录采用 `kebab-case`。
- ArkTS 沿用现有 2 空格缩进及 `client/code-linter.json5`；Java 后端以 `server/CONTRIBUTING.md` 的 4 空格缩进为准，包名保持小写。
- Web 应用之间禁止直接互相导入；共享依赖版本集中维护于 `web/pnpm-workspace.yaml`，更新依赖时同步锁文件。Web 详细约定以 `web/CONTRIBUTING.md` 为准。

## 测试要求

- 服务端使用 JUnit Jupiter 与 Spring Boot Test；跨模块测试及共享 support 位于 `server/app/src/test/java/`，新增测试放入对应包，命名为 `*Tests.java`，覆盖正常流程、边界与失败路径。
- Web 尚无测试运行器或 `pnpm test`；提交前执行格式、lint、类型检查和构建。新增测试按 `web/CONTRIBUTING.md` 接入，命名为 `*.test.ts` / `*.test.tsx`。
- 客户端已声明 Hypium/Hamock 依赖，但尚无测试用例；修改需分别验证手机和平板，记录设备与复现步骤。当前仓库未设置覆盖率阈值；不要将构建成功等同于功能验收。

## 提交与 Pull Request

沿用历史中的 Conventional Commits 与中文描述，例如 `feat(client): 新增练习页面`、`fix(server): 修复启动配置`。提交聚焦单一目的，scope 使用实际模块。

PR 说明变更目的、影响模块、验证命令及结果；关联已有 issue 或设计文档。界面变更附截图，配置变更说明环境要求；无法执行的验证须明确标注。

## 配置安全

不得提交签名材料、口令、令牌、`.env*.local` 或构建产物。修改 `client/build-profile.json5` 时检查差异，避免带入本机签名配置。
