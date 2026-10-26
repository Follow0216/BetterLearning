# server 工程开发约定

本文件作用于 `server/`，优先级高于仓库根 `CONTRIBUTING.md`。

## Java 代码风格

- 使用 **4 空格**缩进，续行 8 空格。
- 遵循阿里巴巴 Java 开发规范：类/方法/字段命名、异常处理、日志与集合使用均按该规范执行。
- 不引入 Lombok、JJWT；JWT 使用 Spring Security OAuth2 Resource Server/Jose。
- 持久化使用 MyBatis-Plus（`mybatis-plus-spring-boot4-starter` 3.5.17）+ Flyway，已获用户批准替换原 Spring JDBC 实现；不叠加原生 MyBatis starter，Spring JDBC 不用于新的业务数据访问。

## 分层

Maven 模块依赖固定为 `app → user/learning/agent/common`、`user/learning/agent → common`。业务模块不依赖 app，user 与 agent 不互相依赖；只有 app 启用 Spring Boot 可执行打包。learning 实现最小题库、多层题集与 M2 当前完整作答存储；agent 在 M3/M4 实现辅导/OCR业务编排与已批准LangChain4j模型适配；app通过端口适配learning的轮次/冻结存储。复用既有依赖，新增外部依赖须先批准。

- `common`：共享错误响应、业务异常、traceId 与脱敏诊断契约，不依赖业务模块。
- `user`：用户/会话控制器、服务、限流与会话存储、JWT、安全认证组件、DAO/Mapper、用户配置与校验；Mapper XML 在 `user/src/main/resources/mapper/`。
- `app`：唯一启动类、配置属性统一注册、CORS/安全链、严格 JSON、全局异常处理和 HTTP trace 过滤器；应用配置与统一 Flyway 迁移在 `app/src/main/resources/`。
- `learning`：题目/题集 service、DAO、Mapper、DTO/VO/DO 与摘要派生；HTTP 控制器在 app 复用 user 的已认证 principal，业务模块之间不互相依赖。真实账号只能从认证上下文取得，所有读写均显式带账号条件。
- 所有既有测试与共享 support 放在 `app/src/test/java/`，保留原包名；跨模块 `@SpringBootTest` 从 app 发现启动类，不发布测试 jar。

各模块沿用以下包结构，禁止跨层反向依赖：

```text
com.betterlearning.server
    controller        # 仅参数绑定、调用 service、返回 VO
    service           # 业务接口
    service.impl      # 业务实现与事务边界
    manager           # 会话存储与认证限流（Redis 实现，故障关闭）
    dao               # 数据访问具体类（MyBatis 调用编排与约束翻译）
    mapper            # MyBatis Mapper：仅 SQL 映射，不含业务规则
    model.dataobject  # 数据库行对象
    model.dto         # 请求对象
    model.vo          # 响应对象
    converter         # 无状态 DO 与 DTO/VO 对象转换工具
    validation        # 输入规范化与合法性校验（简单字段约束内嵌校验器）
    security          # JWT 解析、认证上下文、过滤器
    exception         # 业务异常与全局错误处理
    enums             # 角色、错误码等
    config            # 配置绑定与启动校验
```

## 配置

- 部署配置来自环境变量；标准 Spring 变量直接绑定，`APP_*` 在 `app/src/main/resources/application.properties` 显式映射到 `config/` 下的 `@ConfigurationProperties`。
- 数据库地址、账号、密码与 Redis 连接仍由现有环境变量提供；Flyway 开关当前在 `application.properties` 固定为 `true`，不由环境变量提供。MyBatis-Plus 不新增部署开关。
- 以下为固定框架约定，写在代码或 `application.properties`，不得通过环境变量临时放宽：关闭二级缓存、`localCacheScope=STATEMENT`、`SIMPLE` 执行器、`UserDO` 固定列映射、Mapper XML 固定路径（`classpath*:/mapper/**/*.xml`）；日志实现由代码固定为 `NoLoggingImpl`，不启用含参数 SQL 日志，也不把 mapper 包设为 DEBUG／TRACE。
- 不接分页、逻辑删除、乐观锁插件；`token_version` 不标 `@Version`。
- 数据库连接故障返回 503 `C0001`；唯一约束仍按 SQLState `23505` + 精确约束名翻译。事务回滚覆盖异常仅在非空 `TransactionSystemException.getApplicationException()` 链含 `DataAccessResourceFailureException` 时视为 503，其他事务失败、映射错误与 23505 保持 500，不得改为基于 `SQLException` 的宽泛判定。
- 敏感值（数据库密码、JWT 密钥）不得设置内置默认值，不得写入日志或 `toString`。
- `APP_DOCS_ENABLED` 是上述 `APP_*` 绑定的例外：不经过 `@ConfigurationProperties`，在 `application.properties` 中直接映射 `springdoc.api-docs.enabled` 与 `springdoc.swagger-ui.enabled`，同时控制 `/v3/api-docs` 与 Swagger UI，默认 `false`。
- dev 账号初始化沿用原始环境字面值读取：`APP_DEV_ADMIN_USERNAME/PASSWORD` 是管理员专用例外，默认值仅在 dev 初始化器中为 `admin/admin123`；启用开关在 `application-dev.properties` 映射。不得把该默认值用于正式环境或放宽公共注册规则，不重置已有 ADMIN、不把 USER 提权。
- 新增配置项时同步更新根 `.env.example` 与本模块配置测试。

## 构建与测试

以下 Maven 命令均在 `server/` 执行，通过父 POM 构建 reactor。Checkstyle 规则从 `${maven.multiModuleProjectDirectory}/config/checkstyle/checkstyle.xml` 加载，各模块扫描自己的源码与测试目录。

- 从仓库根目录启动：`./start-server.sh`（保留 `--check` 和原环境加载规则）。脚本先执行 `./mvnw -pl app -am -DskipTests package`，再用 JDK 17 运行 `server/app/target/server.jar`；跳过测试不代表验收通过。
- 已手工导出环境变量时：`./mvnw -pl app -am -DskipTests package` 后 `java -jar app/target/server.jar`。
- 禁止对 reactor 使用 `-pl app -am spring-boot:run`，该目标也会在依赖模块执行；需要 Maven 开发启动时，先 `./mvnw -pl app -am -DskipTests install`，再单独 `./mvnw -pl app spring-boot:run`。

- 格式检查：`./mvnw validate`（validate 阶段绑定 Checkstyle）或 `./mvnw checkstyle:check`
- 格式规则：`maven-checkstyle-plugin` 3.6.0（Checkstyle 9.3），配置 `config/checkstyle/checkstyle.xml`，覆盖各模块 `src/main/java` 与 `src/test/java`；只检查 Tab、文件末尾换行、4 空格基本缩进与 8 空格续行、`} else/catch/finally` 同行，不代表整本阿里巴巴手册合规，不得据此宣称全量合规。
- 违规时 validate 失败，不得通过关闭检查或大范围 suppress 绕过。
- 构建：`./mvnw -DskipTests package`
- 全量测试：`./mvnw test`（默认执行全部测试，不跳过容器测试）
- 仅单元与配置边界测试：`./mvnw test -Punit`（排除带 `integration` 标签的测试）
- 仅容器集成测试：`./mvnw test -Pintegration`（只执行带 `integration` 标签的测试，需 Docker）
- 配置边界测试不得依赖 PostgreSQL/Redis；容器集成测试统一标注 `@Tag("integration")`，不得通过禁用或跳过测试掩盖未验证。
- 已有完整的 Spring 上下文集成测试：`@SpringBootTest(webEnvironment = RANDOM_PORT)` + PostgreSQL/Redis Testcontainers，覆盖注册、登录、资料、改密、退出、安全链、JWT 拒绝、Redis 故障、Swagger 开关与并发场景；需 Docker 可用。
- 单元测试与配置边界测试不依赖容器；默认 `./mvnw test` 会同时启动上述容器测试。


## M2 当前作答文件边界

- `APP_FILES_ROOT` 必填，必须为可写绝对持久目录；JSON、完整合成 PNG、干净题面 PNG 全部写入并 force／原子 rename 后，才允许数据库发布当前 fileRef。配置由 app 注册，learning 不依赖 user/app。
- 保存／完成／继续锁定同一账号题目；条件覆盖只接受当前 baseFileRef，原包重试只对当前 saveRequestId＋摘要去重。完成前必须有成功保存，已完成先继续才可写入。
- 文件 GET 每次按账号、题号、当前引用授权；不挂静态目录。正常覆盖与本次失败候选做局部清理；不增加启动扫描、定时回收、历史表或通用恢复框架。
- multipart 限额、两组整格、世界坐标、错误码及 HTTP 字段以根 `docs/design/mvp-m2-contract.md` 为准。新增当前作答 FK 后，测试清表须同时纳入 question_answer。
