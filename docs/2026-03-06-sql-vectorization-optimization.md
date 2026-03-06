# 2026-03-06 SQL 查询向量化优化记录

## 背景
- 目标：优化 SQL 历史向量化样本质量，提升后续自然语言 RAG 检索命中率。
- 问题：历史 SQL 向量文本主要依赖原始 SQL 片段，缺少归一化与关键语义标签，在语义召回场景下命中不稳定。

## 本次修改

### 1) SQL 历史向量文档增强
文件：`apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagIngestionServiceImpl.java`

- 新增 SQL 归一化处理：
  - 将字符串字面量统一替换为 `<str>`。
  - 将数字字面量统一替换为 `<num>`。
  - 压缩空白并转为小写，降低同义 SQL 的向量离散度。
- 新增 SQL 关键词标签提取：
  - 提取 `select/update/delete/join/group by/order by/where/with/limit` 等关键词，作为结构语义标签。
- 将 `normalized_sql_text` 与 `sql_keyword_tags` 写入 SQL 历史向量 metadata。
- 将“SQL关键词/SQL归一化”追加到历史 SQL 的向量化文本中，强化自然语言到 SQL 语义的匹配桥接。

### 2) SQL 分片向量文档增强
文件：`apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagIngestionServiceImpl.java`

- 原先 SQL 分片仅对 `fragment_text` 直接向量化。
- 现改为分片上下文文档向量化，包含：
  - 数据库名、分片类型、涉及表、涉及列、SQL关键词、分片 SQL、分片归一化。
- 将 `sql_keyword_tags` 透传到分片 metadata，便于后续检索侧做混合召回策略。

## 影响评估
- 对外接口无变更。
- 仅增强向量样本质量，不改变 SQL 执行逻辑。
- 兼容现有 collection 结构（新增 metadata 字段为向后兼容）。

## 验证
- 尝试执行后端 clean 构建：`mvn -f apps/server/pom.xml clean package -DskipTests`
- 当前环境构建失败，原因为依赖仓库 `https://repo.spring.io/milestone` 返回 `403`，导致 Spring Boot parent POM 无法解析（非本次代码逻辑错误）。

## 追加记录（2026-03-06）- 向量对象扩展与检索重排骨架

### 本次补充
- 扩展向量集合配置，新增两类对象集合：
  - `metric_term`（业务术语/口径）
  - `example_sql`（问法+SQL 语义样例）
- 检索链路补齐多桶召回：
  - 原有 `schema_table/schema_column/sql_history`
  - 新增 `metric_term/example_sql`
- 在检索侧增加“统一 rerank 层”工程骨架（可开关）：
  - 新增 `rag.rerank.enabled/alpha/beta/gamma` 配置
  - 保留关闭时降级路径（仅按向量原始分数）
  - 开启时按 `final_score = α*vector + β*onnx_proxy + γ*rule_bonus` 重排
  - 其中 `onnx_proxy` 作为当前阶段过渡打分，后续可替换为真实 ONNX rerank 推理结果
- Prompt 上下文拼装新增分段：
  - `【命中业务术语】`
  - `【命中SQL样例】`

### 主要变更文件
- `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/model/RagCollectionNames.java`
- `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagRetrievalServiceImpl.java`
- `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagIngestionServiceImpl.java`
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/RagVectorizeQueueServiceImpl.java`
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/AiServiceImpl.java`
- `apps/server/src/main/resources/application.yml`

### 说明
- 本阶段为“规划执行落地的第一步”：先打通多对象集合与统一重排接口，不改变既有 SQL 执行逻辑。
- `query_history` 仍仅作为历史语义样例，不作为 schema 真值来源。
- 真正的 ONNX rerank 模型推理与特征拼接仍可在此骨架上继续替换实现。

## 追加记录（2026-03-06）- 向量召回链路带上会话记忆

- 根据反馈补齐：当会话记忆开启时，RAG 检索输入会携带会话记忆信息，不再仅使用当前用户问题。
- 具体实现位于 `AiServiceImpl`：
  - 新增 `buildRetrievalInputForRag(...)`，在构造向量检索输入时注入：
    - 最近窗口会话摘要（`会话窗口摘要`）
    - 向量记忆召回结果（`会话向量记忆召回`）
  - 在 `generateSql / autoQuery / repair / generateChart` 四条调用 RAG 的链路统一替换为该方法。
- 关闭会话记忆时保持原行为（仅基于当前 prompt + 额外上下文），保证可降级。

## 追加记录（2026-03-06）- 提交给 LLM 的内容补充上下文

- 根据反馈补齐：不仅向量检索输入要携带上下文，**提交给 LLM 的用户提示词也要显式带上上下文信息**。
- 在 `AiServiceImpl#buildProviderUserPrompt(...)` 中新增：
  - `检索增强输入(含会话记忆)` 段落。
  - 该段落复用 `buildRetrievalInputForRag(req)`，因此在会话记忆开启时会自动携带会话窗口摘要与向量记忆召回内容。
- 结果：
  - 向量召回阶段与 LLM 生成阶段使用一致的增强上下文输入语义，减少多轮场景下信息偏差。

## 追加记录（2026-03-06）- 本地 ONNX rerank 落地

- 根据反馈将原“onnx_proxy 重排骨架”升级为**本地 ONNX rerank 实现**，并保持可降级。
- 新增服务：
  - `RagRerankService`
  - `OnnxLocalRerankServiceImpl`
- 实现要点：
  - 参考本地向量化 ONNX 运行方式，使用 ONNX Runtime 在本地加载 `rag.rerank.model-dir/model-file-name`。
  - 支持 provider 配置（AUTO/CPU/CUDA）与 CUDA 自动回退 CPU。
  - 按桶构造特征（vector_score/schema_hit/time_signal/hit_coverage/recency_decay/bucket_code）并送入 ONNX 模型推理。
  - 兼容输出 `float[]/float[][]`，统一归一化为 `[0,1]` 评分。
  - 模型缺失或运行失败时返回空评分，由检索层自动降级到“向量分+规则分”。
- 检索融合公式保持：
  - `final = α * vector_score + β * onnx_rerank_score + γ * rule_bonus`。
- 额外改动：
  - `RagRetrievalServiceImpl` 改为依赖 `RagRerankService`，并在请求日志中输出 rerank runtime provider。
  - `application.yml` 增加 rerank 模型与运行时配置项。
