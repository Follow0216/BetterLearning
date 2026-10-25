# P0 技术决策记录

状态：架构建议已获总体通过；具体契约与技术验证结果提交 P0 审核。日期：2026-10-25。依赖版本以验证目录实际记录为准，不以网页“最新版本”作为自动升级理由。

## ADR-001：Temporal Java 负责可靠执行

**选择**：沿 Java 17/Spring Boot 和现有 LangChain4j 构建有限角色流水线，Temporal SDK 管理 Workflow/Activity。Workflow 只做确定性调度；数据库、模型、文件和检索调用均置于 Activity。运行输入与步骤输出尽量只存 ID/摘要，避免把手写图、笔记正文和模型长文本写进 Temporal 历史；Temporal 使用独立数据库/权限，历史保留默认建议 7 天。

原因：首版最重要的难点是客户端抬笔/断线、进程重启、重复投递、取消与收费调用，而非动态图语法。Temporal 不能替代业务幂等账本，也不能保证模型供应商“只计费一次”。参见官方 [Activity 定义](https://docs.temporal.io/activity-definition)、[Workflow 定义](https://docs.temporal.io/workflow-definition) 和 [Java 取消](https://docs.temporal.io/develop/java/workflows/cancellation)。

试验使用 SDK `1.39.0`，Boot `4.0.8`，LangChain4j `1.22.0`。仅在隔离验证 POM 中引入，不改 server POM。SDK TestWorkflowEnvironment 的内存历史重放不等于真实 Temporal Server 进程宕机恢复；详细范围见 [实测](../../testing/multi-agent-p0/temporal-spike/temporal-report.md)。生产 Server 版本与 SDK 组合需在部署验收时固定 digest 并重测，不能从 SDK 版本号推导。

首版可保持同一代码仓库，以独立 worker 进程部署 agent 执行器，避免 HTTP 进程重启阻断任务。API 与 worker 共同使用业务库并通过端口访问资料；模型密钥仅配置给需要执行模型的服务进程。

## ADR-002：暂不引入 LangGraph

LangGraph 提供图状态、检查点和中断恢复，并非没有恢复能力；其 [持久化文档](https://docs.langchain.com/oss/python/langgraph/persistence) 明确区分线程检查点与跨线程存储。当前有限流水线使用 Temporal 已能表达所需依赖，同时再引入 Python/TypeScript 图服务会增加部署、状态映射与取消传播成本。

后续出现可测量需求再重评：大量动态分支、多轮工具规划、人工在图中审批、需要 Python 生态模型/工具。若采用，Temporal 仍只管理外层业务任务生命周期；LangGraph 管理单个受限推理子任务，明确唯一恢复所有者，不能让两个框架同时自动重试同一收费步骤。P0 不实现 LangGraph 双编排原型。

## ADR-003：PostgreSQL + pgvector，原始资料仍为事实源

选择在现有 PostgreSQL 旁增加 pgvector 扩展，避免首版维护独立向量数据库。来源权限、版本和向量可在同一数据库查询中连接过滤。验证镜像固定版本与 digest；实际见 [pgvector 实测](../../testing/multi-agent-p0/pgvector-report.md)。

先用账号过滤后的精确向量检索验证质量与基线性能；数据量增长且实测需要时再建 HNSW。近似索引与过滤可能影响召回数量，不能把有索引视作性能/质量通过。全文和向量融合遵循 [pgvector 官方混合检索说明](https://github.com/pgvector/pgvector#hybrid-search)。不引入 Elasticsearch/Milvus/Qdrant 等额外服务。

中文检索需要词项处理与方法标签。合成向量只能验证向量运算、隔离和融合排序，不能证明 embedding 能理解“同解法不同题面”。方法词表、模型与重排策略由 [评测方案](evaluation.md) 的真实案例决定。

## ADR-004：模型适配与质量门禁

复用现有 LangChain4j 生成模型适配，角色通过结构化输入/输出与提示词版本隔离，不要求首版每个角色使用不同供应商。不将模型提供的 confidence 作为唯一正确性判据，也不向学生显示原始内部推理。

embedding 候选优先比较已许可的中文模型与现有供应商兼容服务；小规模基准可采用 [BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)。这是候选而非已选生产模型；该模型的语言/许可证/使用说明不证明数学检索质量。首版模型 ID、版本、维度、许可、token 限制、延迟、成本和召回分数全部记录后再冻结；不得使用未经评估的默认相似度阈值。

P0 不调用未获单独配置的付费模型，不读取真实用户笔记充当测试数据。真实生成质量与 embedding 评测是后续里程碑的放行条件，不以 fake model 或三维向量替代。

## ADR-005：版本化引用与业务发布权威

引用对齐不可变 source/version/anchor，业务主库实时控制可见性，向量索引只是派生数据。取消与发布由一个数据库事务序列化决定；Temporal 状态用于运维与调度，不直接绕过业务 CAS。用户显式 retry 创建新 run，终态永不回退。

保留旧 V1 历史只读；新写入口使用 `/api/v2/learning`。上线时先统一账号活动槽位并清理/终结旧活动任务，再启用 V2。回滚时关闭新创建、允许既有任务排空或取消、保留已发布版本可读，不删除新表或把 V2 任务重放成 V1 收费请求。
