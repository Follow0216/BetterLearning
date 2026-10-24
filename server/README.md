# BetterLearning 服务端

认证与用户资料服务，基于 Spring Boot 4.0.8、Java 17、PostgreSQL、Redis。本服务只使用已准备好的基础设施，不负责创建数据库实例、Redis 实例或系统账号。

## 0. Maven 模块边界

`server/pom.xml` 是父聚合 POM，构建一个 Spring Boot 应用：

| 模块 | 职责 |
| --- | --- |
| `common` | 公共错误响应、业务异常、traceId 与脱敏诊断契约 |
| `user` | 用户/会话 API、注册登录、资料改密、JWT、安全认证、Redis 会话与限流、DAO/Mapper |
| `learning` | M1 当前账号题库与题集存储、确认去重及摘要 |
| `agent` | 智能体能力骨架，M0 不调用模型、不引入 AI 运行依赖 |
| `app` | 唯一启动入口、HTTP/安全装配、应用配置、统一 Flyway 迁移、跨模块测试 |

依赖为 `app → user/learning/agent/common`、`user/learning/agent → common`；业务不依赖 app，user 与 agent 不互相依赖。四个依赖模块是普通 jar，只有 app 生成可执行的 `app/target/server.jar`。原 Java 包名保留为 `com.betterlearning.server` 下的分层包；启动类仍位于根包，因此会扫描各模块中的组件。

## 1. 环境要求

- JDK 17。
- PostgreSQL。自动化测试使用 `postgres:18.3` 镜像。
- Redis。自动化测试使用 `redis:7.4.2` 镜像。
- Docker。集成测试通过 Testcontainers 启动临时 PostgreSQL 与 Redis，Docker 必须可用。
- Maven 使用仓库自带的 `server/mvnw`，无需单独安装；wrapper 固定 Maven 3.9.11。

## 2. 本地配置

在仓库根目录手工复制模板：

```bash
cp .env.example .env.local
```

然后编辑 `.env.local`：

1. 填写 `SPRING_DATASOURCE_PASSWORD`。
2. 生成自己的 JWT 密钥并填入 `APP_JWT_SECRET_BASE64`：

   ```bash
   openssl rand -base64 32
   ```

`.env.local` 已被 `.gitignore` 忽略，**不得提交**，也不要写入任何共享密钥。仓库中的 `.env.example` 只保留空密码和空密钥，不提供可用密钥。

### 2.1 环境变量

| 变量 | 默认值 | 必填 | 说明 |
| --- | --- | --- | --- |
| `SERVER_PORT` | `8080` | 否 | HTTP 端口 |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://127.0.0.1:5432/betterlearning` | 否 | 数据库地址 |
| `SPRING_DATASOURCE_USERNAME` | `betterlearning` | 否 | 数据库用户名 |
| `SPRING_DATASOURCE_PASSWORD` | 无 | **是** | 数据库密码 |
| `SPRING_DATA_REDIS_HOST` | `127.0.0.1` | 否 | Redis 主机 |
| `SPRING_DATA_REDIS_PORT` | `6379` | 否 | Redis 端口 |
| `SPRING_DATA_REDIS_DATABASE` | `0` | 否 | Redis 库编号 |
| `SPRING_DATA_REDIS_USERNAME` | 空 | 否 | Redis 用户名 |
| `SPRING_DATA_REDIS_PASSWORD` | 空 | 否 | Redis 密码 |
| `APP_JWT_SECRET_BASE64` | 无 | **是** | Base64 编码的 HMAC 密钥，解码后至少 32 字节 |
| `APP_JWT_ISSUER` | 无 | **是** | JWT 签发者 |
| `APP_JWT_AUDIENCE` | 无 | **是** | JWT 受众 |
| `APP_JWT_ACCESS_TOKEN_TTL` | `PT2H` | 否 | 令牌有效期，默认 2 小时，不自动续期 |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:3001` | 否 | 逗号分隔的精确 origin，不支持 `*`、通配符或路径 |
| `APP_DOCS_ENABLED` | `false` | 否 | 同时控制 Swagger UI 与 `/v3/api-docs`；本地示例为 `true` |
| `APP_AUTH_LOGIN_IP_LIMIT` | `30` | 否 | 登录按来源 IP 的固定窗口上限 |
| `APP_AUTH_LOGIN_USERNAME_LIMIT` | `10` | 否 | 登录按规范化用户名的固定窗口上限 |
| `APP_AUTH_LOGIN_WINDOW` | `PT1M` | 否 | 登录限流窗口 |
| `APP_AUTH_REGISTRATION_IP_LIMIT` | `10` | 否 | 注册按来源 IP 的固定窗口上限 |
| `APP_AUTH_REGISTRATION_WINDOW` | `PT1H` | 否 | 注册限流窗口 |
| `SPRING_SECURITY_USER_NAME` | 无 | dev 必填 | dev profile 初始化普通 USER 账号的用户名，去首尾空白并转小写 |
| `SPRING_SECURITY_USER_PASSWORD` | 无 | dev 必填 | dev profile 初始化账号的密码，不 trim，8~64 个字符且 UTF-8 不超过 72 字节 |

### 2.2 环境文件加载规则

根目录 `start-server.sh` 按以下优先级加载：

1. 显式指定的 `BETTERLEARNING_ENV_FILE`。
2. 仓库根目录 `.env.local`。
3. 当前进程已有的环境变量。

文件中的同名变量覆盖当前进程变量。显式指定的文件不存在或不可读时脚本直接失败；默认 `.env.local` 缺失但进程环境齐全时仍可启动。

文件解析只支持简化格式：

- 每行 `KEY=VALUE` 或 `export KEY=VALUE`。
- 支持空行、CRLF、独立成行的 `#` 注释，以及成对的单引号或双引号。
- **不支持** shell 变量插值、命令替换、行内注释或多行值。
- 变量名必须匹配 `[A-Za-z_][A-Za-z0-9_]*`。

若仓库路径或环境文件路径包含空格，调用脚本时请给路径加引号，例如：

```bash
"/path/with space/start-server.sh" --check
BETTERLEARNING_ENV_FILE="/path/with space/my.env" ./start-server.sh
```

### 2.3 dev 账号初始化

仅在 `dev` profile 下，`DevUserAccountInitializer` 会在 Flyway 迁移完成后读取
`SPRING_SECURITY_USER_NAME` 与 `SPRING_SECURITY_USER_PASSWORD`，确保 `app_user` 中存在一个
`USER` 角色账号。该账号使用现有 `POST /api/v1/sessions` 与 JWT 登录，不启用 HTTP Basic／表单登录，
不创建 `ADMIN`，也不新增接口。非 dev profile 不注册该初始化器。

- 变量缺失、空白或格式非法时，在写库前阻止启动；错误信息只包含变量名与错误类型。
- 变量按原始字面值读取，密码中的 `${...}` 不解析、不替换；用户名仍按现有规则规范化与校验。
- 用户名去首尾空白并转小写；密码不 trim。密码要求 8~64 个字符、不能全为空白，且 UTF-8 不超过 72 字节。
- 同名账号不存在时插入，数据库默认 `role=USER`、`token_version=0`。
- 同名账号已存在且角色为 `USER`、密码匹配时，不修改账号、哈希、令牌版本或资料。
- 同名账号密码不同或角色不是 `USER` 时启动失败，不覆盖、不改密、不提权。
- 并发创建遇到用户名唯一冲突时，会在干净事务中回读并复核；其他数据库异常继续向上抛出。

`application-dev.properties` 不再引用 `SPRING_SECURITY_USER_*`，避免配置文件再次引用变量并解析占位符；
标准 `SPRING_SECURITY_USER_*` 仍可按 Boot relaxed binding 绑定到 `spring.security.user.*`，
但业务账号由 dev 初始化器直接读取原始环境变量负责。业务登录始终使用数据库用户与 JWT，不启用 Basic。

### 2.4 dev 管理员

显式启用 `dev` profile 时，`DevAdminAccountInitializer` 默认创建 `admin/admin123` 管理员。可用 `APP_DEV_ADMIN_USERNAME` 与 `APP_DEV_ADMIN_PASSWORD` 覆盖（密码按字面读取，7–64 字符且 UTF-8 不超过 72 字节），或用 `APP_DEV_ADMIN_ENABLED=false` 禁用。非 dev 不创建管理员。既有普通 dev USER 初始化要求保持不变，其用户名不能与管理员相同。

已有 ADMIN 密码匹配时不修改哈希、资料或 token_version；不匹配时启动失败而不重置，已有 USER 同名时失败而不提权。更改初始化配置不会撤销旧 ADMIN 账号；两个初始化器独立提交，失败时可能保留另一个已创建的 dev 账号。公共注册仍只能 USER，且仍要求密码至少 8 字符。

管理员也用 `POST /api/v1/sessions` 登录，后台检查响应 `user.role=ADMIN`，并用 Bearer 令牌请求 `GET /api/v1/admin/session` 确认服务端权限。后者返回既有 UserVO；普通 USER 为 `403 A0300`。不提供第二套登录、角色管理或后台数据查询系统。

## 3. 数据库迁移

M1 新增 V2 的 `question_collection`、`question` 两表；V1 保持不变。题目以 `(account_id, request_id)` 唯一键实现确认去重，账号/题集复合外键限定归属。待整理是 `collection_id=NULL` 的虚拟题集，无演示数据初始化。

应用启动时由 Flyway 自动执行 `server/app/src/main/resources/db/migration` 下的迁移。请使用为本服务单独准备的数据库，不要把迁移指向共享库或生产库。

### 3.1 持久化实现

用户表通过 MyBatis-Plus 3.5.17 访问，starter 传递 MyBatis 3.5.19 与 mybatis-spring 4.0.0。`UserDao` 是唯一生产数据访问类，构造注入 `UserMapper`，并在类内完成唯一约束与连接故障翻译；`UserMapper` 位于同级的 `mapper` 包，`UserDO` 使用显式 `@TableName`／`@TableId`／`@TableField` 映射，`user/src/main/resources/mapper/UserMapper.xml` 固定注册的 `RETURNING account_id`、联系方式动态 `UPDATE` 与密码 CAS 语句。

Mapper 扫描仍固定为 `com.betterlearning.server.mapper`，XML 通过 `classpath*:/mapper/**/*.xml` 从 user 模块加载。

二级缓存关闭、`localCacheScope=STATEMENT`、`SIMPLE` 执行器、驼峰映射与 `NoLoggingImpl` 由 `config/MybatisConfiguration` 固定，不新增部署环境变量，也不启用含参数值的 SQL 日志。

## 4. 启动

在仓库根目录：

```bash
./start-server.sh --check
./start-server.sh
```

`--check` 只检查 JDK 17 与必填环境变量是否存在，**不验证**数据库/Redis 连接、JWT 格式或 Spring 配置；真正启动时由 Spring Boot 完成全部配置校验。

脚本会先用 Maven reactor 打包 app 及其依赖，再以 JDK 17 启动 app jar；打包使用 `-DskipTests`，不代表测试或功能验收已通过。`--check` 不执行打包。

也可以手工导出环境变量后打包启动：

```bash
export SPRING_DATASOURCE_PASSWORD='...'
export APP_JWT_SECRET_BASE64='...'
export APP_JWT_ISSUER='betterlearning-server'
export APP_JWT_AUDIENCE='betterlearning-api'
cd server
./mvnw -pl app -am -DskipTests package
java -jar app/target/server.jar
```

打包并运行独立进程：

```bash
cd server
./mvnw -DskipTests package
java -jar app/target/server.jar
```

`./mvnw package` 会先运行测试；`-DskipTests` 只跳过测试执行，不会跳过源码与测试源码编译。

如需 `spring-boot:run` 的开发启动模式，可在 `server/` 先执行 `./mvnw -pl app -am -DskipTests install`，将同版本模块安装到本地 Maven 仓库，再执行 `./mvnw -pl app spring-boot:run`。不要执行 `./mvnw -pl app -am spring-boot:run`，它会对聚合及依赖模块一起执行启动目标。

## 5. REST 接口

| 方法 | 路径 | 认证 | 成功状态 | 说明 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/users` | 匿名 | `201` | 注册用户 |
| `POST` | `/api/v1/sessions` | 匿名 | `201` | 登录并签发 JWT |
| `GET` | `/api/v1/users/me` | Bearer | `200` | 获取当前用户资料 |
| `PATCH` | `/api/v1/users/me` | Bearer | `200` | 绑定、更换或解绑手机号/邮箱 |
| `PATCH` | `/api/v1/users/me/password` | Bearer | `204` | 修改密码 |
| `DELETE` | `/api/v1/sessions/current` | Bearer | `204` | 退出当前会话 |

### 5.1 Swagger

开启 `APP_DOCS_ENABLED=true` 后：

- Swagger UI：<http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

开关默认关闭，`.env.example` 中本地示例为 `true`。

推荐操作顺序：

1. `POST /api/v1/users` 注册。
2. `POST /api/v1/sessions` 登录，复制响应中的 `accessToken`。
3. 点击 Swagger UI 的 **Authorize**，只填写 JWT 本身，不要手动添加 `Bearer` 前缀；界面会自动生成 `Authorization: Bearer <token>`。
4. `GET /api/v1/users/me` 查看资料。
5. `PATCH /api/v1/users/me` 绑定、更换或解绑联系方式。
6. `PATCH /api/v1/users/me/password` 修改密码；成功后该账号此前签发的所有 JWT 立即失效。
7. 重新执行 `POST /api/v1/sessions` 登录，获取新 `accessToken`，并再次点击 **Authorize** 填入新 JWT。
8. `DELETE /api/v1/sessions/current` 退出当前会话；必须使用重新登录后的新 token，旧 token 已无法通过认证。

### 5.2 注册与登录

- 注册只接受 `username`、`password`、`confirmPassword` 三个字段，角色固定为 `USER`，不接受客户端指定角色或账号编号。
- 账号编号由数据库生成，响应中以字符串 `accountId` 返回，避免前端精度丢失。
- 用户名先去首尾空白，再把英文字母转小写，然后校验规范化后的 3~32 个字符；仅支持中文、ASCII 字母、数字和下划线。
- 密码不 trim；注册要求 8~64 个字符且 UTF-8 不超过 72 字节，登录只要求非空且不超过上限。
- 手机号和邮箱只在个人中心填写，注册时不接收，也不用于登录或找回密码。

### 5.3 个人资料

`PATCH /api/v1/users/me` 只处理请求体中出现的字段：

- 字段缺失：不修改。
- 显式 `null`：解绑该联系方式。
- 空串或格式错误：`400 A0400`。
- 手机号或邮箱已被其他账号占用：`409 A0154` 或 `409 A0155`，原值保持不变。

### 5.4 改密与退出

- 改密需要旧密码、新密码和确认密码；成功后 `token_version` 递增，**该账号此前签发的所有 JWT 立即失效**，不签发新令牌。
- 若密码已被其他请求修改，返回 `409 A0444`，本次修改未生效，需要重新登录后再试。
- 退出只删除当前 JWT 的 `jti` 对应会话，同账号其他端的会话继续有效。
- JWT 默认 2 小时有效，不自动续期；过期后需要重新登录。

### 5.5 错误码

对外 `code` 为 5 位字符串：首位 A 表示用户端错误、B 表示系统执行错误、C 表示第三方服务错误，后四位为编号。编号与 HTTP 状态码相互独立。完整说明与触发场景见本页第 5.5 节错误码表。

| 状态 | `code` | Java 枚举 | 含义 |
| --- | --- | --- | --- |
| `400` | `A0400` | `INVALID_PARAM` | 字段校验失败；改密时旧密码错误体现在 `fieldErrors.oldPassword` |
| `400` | `A0427` | `INVALID_JSON` | 请求体格式错误、含未知字段或类型不匹配 |
| `401` | `A0200` | `UNAUTHORIZED` | 未认证、令牌无效/过期/已失效，或用户名密码错误 |
| `403` | `A0300` | `FORBIDDEN` | 已认证但无权限，或 CORS origin 被拒绝 |
| `404` | `A0606` | `NOT_FOUND` | 路径不存在，或文档端点未开启 |
| `405` | `A0507` | `METHOD_NOT_ALLOWED` | 请求方法不支持 |
| `415` | `A0428` | `UNSUPPORTED_MEDIA_TYPE` | 媒体类型不受支持 |
| `409` | `A0111` | `USERNAME_EXISTS` | 用户名已存在 |
| `409` | `A0154` | `PHONE_ALREADY_BOUND` | 手机号已绑定其他账号 |
| `409` | `A0155` | `EMAIL_ALREADY_BOUND` | 邮箱已绑定其他账号 |
| `409` | `A0444` | `PASSWORD_CHANGE_CONFLICT` | 密码已被其他请求修改，本次改密未生效 |
| `429` | `A0501` | `RATE_LIMITED` | 注册或登录触发限流 |
| `503` | `C0001` | `SERVICE_UNAVAILABLE` | 数据库或 Redis 不可用；请求结果可能不确定，应先查询状态确认，勿盲目重试 |
| `500` | `B0001` | `INTERNAL_ERROR` | 服务器内部错误 |

错误响应统一包含 `code`、`message`、`traceId`；`traceId` 同时写入响应头 `X-Request-Id`。登录失败统一返回 `401 A0200`，不区分账号不存在与密码错误，避免泄露账号是否存在。

### 5.6 管理员

- 非 dev 环境不提供默认管理员；dev profile 按第 2.4 节初始化默认管理员。
- 没有公开的角色提升接口或管理界面。
- 如确需受控提升，只能在数据库中按**指定用户名**执行，并先确认目标唯一：

  ```sql
  UPDATE app_user SET role = 'ADMIN' WHERE username = '要提升的用户名';
  ```

  `username` 有唯一约束，正常情况下该语句只影响一行；执行前应先 `SELECT` 确认目标账号。

## 6. 代码格式检查

```bash
cd server
./mvnw validate          # validate 阶段自动执行 Checkstyle
./mvnw checkstyle:check  # 仅执行 Checkstyle
```

使用固定版本 `maven-checkstyle-plugin` 3.6.0（默认 Checkstyle 9.3），规则位于 `config/checkstyle/checkstyle.xml`，覆盖各模块 `src/main/java` 与 `src/test/java`；父 POM 使用 `${maven.multiModuleProjectDirectory}` 定位同一规则文件。该配置只检查格式子集，不代表阿里巴巴 Java 开发手册全量合规；违规时构建失败。

## 7. 测试

```bash
cd server
./mvnw test                # 全量：单元、配置边界与容器集成测试
./mvnw test -Punit         # 仅单元与配置边界测试，不启动容器
./mvnw test -Pintegration  # 仅容器集成测试，需 Docker
./mvnw package
```

既有测试原样迁入 `app/src/test/java/`，共享 `support/` 与 `security-it` 测试端点也留在 app 的测试类路径，避免跨模块 Spring 测试无法发现启动类。单元、配置及集成测试继续保留原包名与标签，不发布测试 jar。

默认 `./mvnw test` 通过 reactor 执行全部测试，不跳过任何测试类。依赖 PostgreSQL/Redis 的测试类标注 `@Tag("integration")`：`-Punit` 排除它们，`-Pintegration` 只执行它们。运行容器测试时 Docker 必须可用，Testcontainers 会启动 PostgreSQL 18.3 与 Redis 7.4.2。配置绑定与校验测试不依赖外部服务。

现有测试类已覆盖的主要范围：

| 范围 | 测试类 |
| --- | --- |
| 配置绑定与启动校验 | `config/AppPropertiesBindingTests`、`config/AppPropertiesValidationTests` |
| 迁移与持久化 | `dao/UserPersistenceIntegrationTests`、`exception/UniqueConstraintMappingIntegrationTests` |
| MyBatis 迁移与故障边界 | `config/MybatisConfigurationTests`、`dao/MybatisRuntimeIntegrationTests`、`dao/UserDaoTests`、`security/DatabaseOutageIntegrationTests`、`exception/GlobalExceptionHandlerLoggingTests` |
| 注册、校验与 JSON 绑定 | `controller/UserRegistrationIntegrationTests`、`validation/UserRequestValidationTests`、`model/dto/UserRequestJacksonTests`、`converter/UserConverterTests`、`model/dataobject/UserDOTests` |
| 登录与限流 | `controller/SessionLoginIntegrationTests`、`manager/RateLimitManagerIntegrationTests`、`service/impl/SessionServiceImplTests` |
| JWT 与会话 | `security/JwtTokenServiceTests`、`manager/SessionManagerIntegrationTests`、`service/impl/SessionServiceImplLogoutTests` |
| 安全链、CORS 与依赖故障 | `security/SecurityFilterChainIntegrationTests`、`security/SecurityDependencyFailureIntegrationTests`、`security/JwtRejectionIntegrationTests`、`security/RedisOutageIntegrationTests`、`security/TraceIdFilterTests`、`exception/GlobalExceptionHandlerTests` |
| 资料、改密与并发 | `controller/UserProfileIntegrationTests`、`controller/ChangePasswordIntegrationTests`、`service/impl/UserServiceImplPasswordTests`、`controller/ConcurrentCredentialIntegrationTests` |
| 退出 | `controller/SessionLogoutIntegrationTests` |
| Swagger/OpenAPI | `controller/ApiDocumentationEnabledIntegrationTests`、`controller/ApiDocumentationDisabledIntegrationTests` |

以上测试类覆盖六个接口的主要成功与失败路径。单次任务只运行直接受影响的测试，不能将局部通过等同于最终验收。

本地启动前需自行准备可用的 PostgreSQL 与 Redis，并按第 2 节配置 `.env.local`。验收使用的临时容器与服务已清理，本仓库不保留任何测试服务或临时密钥。

## 8. M1 真实题库

HTTP 契约以根目录 `docs/design/mvp-m1-contract.md` 为准。接口均要求既有 Bearer 登录，owner 取实时认证 principal：

- `POST /api/v1/learning/questions`：人工确认 Markdown，携带 requestId、subject、bodyMarkdown；入待整理，重试相同请求返回原题目，不同内容重复使用 requestId 为 `409 A0445`。
- `GET /api/v1/learning/questions`：page/pageSize 分页，可按 collectionId 浏览。
- `GET /api/v1/learning/questions/{questionId}`：当前账号详情与原始 Markdown。
- `GET /api/v1/learning/collections`：当前账号题集，固定待整理第一项。

没有独立标题；summary 由服务端生成约 50 字符预览。M1 科目代码仅 `HIGHER_MATHEMATICS`、`OTHER`。不提供题目编辑、题集写操作、搜索、作答、AI 或文件系统。

新增 `LearningIntegrationTests` 与 `DevAdminAccountIntegrationTests` 使用原 Testcontainers PostgreSQL/Redis 基础设施；开发交付不运行构建或测试，具体结果由独立 TEST 记录。

## 9. M2 题集阶段

题集现支持 POST `/api/v1/learning/collections`（name、可空 parentCollectionId）、PATCH `/api/v1/learning/collections/{collectionId}`（仅 name）和 PATCH `/api/v1/learning/questions/{questionId}/collection`（collectionId，inbox 可移回待整理）。题集 GET 节点增加 parentCollectionId；计数只含直接归属，父级不可更改，固定待整理不可改名或作为父级。同级重名为 409 A0450，其他账号资源为 404。

V3 新增父级及同级唯一约束，V1/V2 不改；无题集删除、移动题集或题干编辑。字段以 `docs/design/mvp-m2-contract.md` 题集章节为准。完整作答／文件接口另行固定并交付，本题集提交不提供模拟作答。
