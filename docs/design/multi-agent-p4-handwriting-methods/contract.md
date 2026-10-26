# P4 手写方法候选增量契约 v1

本契约描述可控启用的手写方法候选、阶段结果保留和召回规则。真实 OCR 与人工方法质量需单独验证。

## 1. 原文与候选分离

仅为 SAVED_ANSWER 新增独立 `HandwritingIndexOcr`，通用 `OcrModel` 保持忠实转写。每个既有确定性步骤裁剪图仍只有一次计划 OCR；同一响应返回原文和有限候选，不增加独立分类调用。步骤裁图不足以观察完整方法时允许空候选，不能借题目、questionId、TUTOR_REPLY 或当前 SOLVE 标签补学生方法。

结构化输出精确字段：`schemaVersion="handwriting-ocr-v1"`、`taxonomyVersion="math-methods-v1"`、`stepOrdinal`、`transcript`、`uncertainty=CLEAR|UNCERTAIN`、`methodCandidates[]`。候选字段为 `method`、`evidenceStartUtf16`、`evidenceEndUtf16`、`region{x,y,width,height}`、`state=CANDIDATE|UNCERTAIN|REJECTED`、`reason=VISIBLE_STEPS|INCOMPLETE_STEPS|ILLEGIBLE|CONTRADICTORY|NEGATED`。

原文1–8192 UTF-16单元，保持LF、不trim/改写，不接受非法代理对；候选最多8且canonical唯一，证据区间必须落在原文、不能切断代理对；区域必须是当前裁图内的整数正尺寸矩形。重复/额外字段、类型错误、未知版本/方法、坏区间/区域整份响应拒绝，不回退到第二次通用 OCR 外呼。`CANDIDATE` 只能配 `CLEAR/VISIBLE_STEPS`，表示结构可追溯的召回候选，不代表数学正确、学生掌握或引用成立；不接受模型 confidence 数值作为概率门槛。

候选使用既有受控词中的12类实际方法及alias：配方法、平方差公式、链式法则、换元法、分类讨论、待定系数法、消元法、相似三角形、数学归纳法、反证法、数形结合、因式分解。`一元一次方程`、`二次函数最值` 等题型/目标不是新OCR方法候选。原有显式标签与长尾原文行为兼容；只有受控实际方法可与新推断候选相交，未知长尾仍需原文完整提及。未来扩词必须新词表版本并重新评测。

## 2. 出处、索引与召回

`method_metadata` 是独立可重建投影，含formatVersion、accountId、sourceId/sourceVersion/indexRevision、anchorId/anchorSha256、crop imageSha256/像素尺寸、model和完整结构化结果。服务端绑定身份、摘要与定位，模型不决定source/anchor身份。原 `anchor.text/hash/locator`、chunk.text、embedding输入及 Evidence.claim 均保持 transcript；模型区域只描述裁图内候选出处，不覆盖既有笔划/世界坐标定位器。

默认 `APP_AGENT_HANDWRITING_METHODS_ENABLED=false`，新索引沿原通用转写路径。启用后仅新保存的 SOURCE_VERSION_CREATED 手写索引持久化结构化候选；旧 SOURCE_REINDEX 不因开关变化切换契约。独立 `APP_AGENT_HANDWRITING_METHODS_RECALL_ENABLED=false` 默认只保存候选。打开召回开关后，SQL在同账号ACTIVE/current READY/revision/model/dim过滤内对合法候选作词法加分，Java再验证同版本词表与实际方法交集；UNKNOWN/UNCERTAIN/REJECTED候选不经新推断通道准入。该状态不删除原文明确方法的既有字面召回路径；错误旧解仍可作非权威反思材料，不能由召回断言其解法正确。无合适候选仍可无引用，不放宽纯向量阈值。

推断方法不混入原文，不作为解法正确或引用主张的证明。P3收到的仍是固定原文及OCR/作答状态提示，TEACH/REVIEW须独立检查原文能否支持主张；不能因召回而强制引用。引用发布、resolve与原图读取的实时授权及锚点摘要合同不变。

## 3. 分阶段持久化与恢复

新增 `ma_index_stage`（迁移 V2026102508），每job按 `OCR_STEP/ordinal` 与 `EMBED_BATCH/ordinal` 唯一。记录job账号/source/version/revision、源摘要、fileRef、输入摘要、model、dimensions、schema版本、intent fence、`CALL_INTENT|CONFIRMED|UNKNOWN`、确认输出与SHA、每阶段请求数和tokens。OCR输入摘要为真实裁图PNG SHA；embedding摘要覆盖有序锚点身份/正文摘要/locator，显式按现有batchSize切分，每个意图仅允许一次HTTP批次。

新协议先pin `HANDWRITING_METHODS_V1`，与旧job.call_state隔离。每次外呼前独立事务持久意图；响应校验后结果与用量同事务确认。崩溃后同job只有绑定完全一致且SHA校验通过的CONFIRMED结果可以复用；未确认意图/UNKNOWN不得自动重发。模型/schema/输入变化不能借旧结果继续。已pin协议不因开关切换退回旧OCR路径。旧job无持久结果但call_state非NOT_SENT仍保守UNKNOWN。

begin、confirm与最终发布在账号锁及当前job fence/有效租约下核对ACTIVE source、current sourceVersion、INDEX_PENDING、indexRevision、源摘要/fileRef。方法投影必须匹配本job已确认OCR的原文、原图摘要、步骤和模型；来源新保存/撤回或revision变化先提交时，迟到确认/发布拒绝。OCR与embedding用量独立，复用确认结果不重复累加；未确认可能收费单列，不视为0元。

## 4. 旧资料与预算边界

旧 READY 资料不自动重新读图、补标签或调用embedding。本增量不提供metadata-only免费重建，也不跨indexRevision借用确认结果。已有锚点的普通显式reindex沿现有原文复用与收费确认规则，不声称因此补出隐含方法；要为旧资料新提取结构化候选，需另行明确预算及锚点兼容策略。新保存版本可在受控启用后采用新契约。向量仅在同job、同输入/模型/维度/revision的CONFIRMED embedding阶段复用。

## 5. 验证范围

测试应覆盖：严格JSON/方法与区域校验；不同领域显式/隐式/否定/错误/空候选；原文/摘要/步骤与账号绑定；stage确认后崩溃复用、UNKNOWN不重发、逐批用量；旧版本/删除/revision/fence/迟到竞争；召回开关与任务类别隔离；旧READY无自动外呼及向量复用限制。自动化测试使用合成资料、mock/本机 stub 和独立 PostgreSQL；真实 provider 调用单独授权和记录。

构建、迁移和自动化测试不能替代真实 OCR、方法候选人工 Precision/Recall、跨题手写引用与定位，以及独立 100 例质量验证。


## 阶段保留与候选投影

V09/V10 增加阶段保留与候选投影。来源删除、删除日志恢复和旧版本退休必须清理阶段正文、输入摘要及向量输出；阶段身份/fence/已确认用量/UNKNOWN不确定费用保留，已清理阶段永不复用或重新外呼。升级还须覆盖之前已标purged但仍有阶段输出的记录。

模型候选原声明独立保留。`HandwritingMethodMetadata.eligibleMethods()`使用既有Java否定规则决定服务端推断投影，SQL前40候选排序和Java排序均以这一投影为准；结构化手写的字面受控方法也使用Java正文投影，禁止原文子串旁路重新给否定候选加分。V10通过Java Flyway迁移对已有结构化metadata进行0-provider回填，非法绑定不产生推断投影，不重新识图或重算向量。开关关闭时只保留原有字面通道。
