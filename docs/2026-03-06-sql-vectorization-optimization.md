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

## 追加记录（2026-03-06 16:13）- 本地 Rerank 配置页并排改造

### 本次目标
- 在 AI 配置弹窗中新增“本地 Rerank 模型配置”，并与“向量模型配置”并排展示。
- 配置不仅可保存展示，还需在后端检索链路中实际生效。

### 关键改动
- 前端（`apps/desktop/src/App.vue`、`apps/desktop/src/types/index.ts`、`apps/desktop/src/style.css`）：
  - 在“向量化配置”页新增双列卡片布局：左侧向量模型目录，右侧本地 Rerank 配置。
  - 新增 Rerank 配置项：启用开关、模型目录、模型文件名、执行 Provider（AUTO/CPU/CUDA）、CUDA 设备 ID、特征维度。
  - 新增本地目录选择函数 `pickRagRerankModelDir`，并在保存前做参数归一化与边界收敛。
- 后端配置持久化（`RagConfigSaveReq/RagConfigVO/RagEmbeddingConfigEntity/RagConfigMapper/RagConfigServiceImpl`）：
  - 扩展 RAG 配置 DTO/实体/Mapper，支持上述 Rerank 字段 get/save。
  - 统一默认值与安全收敛（feature size >= 6、cuda id >= 0、provider 仅 AUTO/CPU/CUDA）。
- 数据库与迁移（`AiConfigMigrationRunner`、`schema.sql`）：
  - 为 `rag_embedding_config` 增加 rerank 相关列，并兼容历史库升级/重建。
- 运行时生效：
  - `OnnxLocalRerankServiceImpl` 改为读取 `RagConfigService` 配置（短 TTL 缓存），配置变化可触发会话重建。
  - `RagRetrievalServiceImpl` 的 rerank 开关改为读取 RAG 配置，不再仅依赖 `application.yml` 固定值。

### 规范自检（backend-api-design）
- 已按规范检查，未发现违规：
  - 接口仍使用 GET/POST；
  - 请求/响应均为 DTO/VO（未引入 Map 作为接口载荷）；
  - SQL 仅在 Mapper 注解中，Service 未拼接 SQL；
  - 新增 DTO 字段已补充中文注释，关键逻辑补充了中文注释。

### 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 后端 Maven 打包：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18082` 启动成功；`http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后执行 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6048`，`HTTP/1.1 200 OK`。

## 追加记录（2026-03-06 16:17）- 最终回归验证

### 验证补充
- 后端二次打包验证：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 后端 clean 启动验证：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18083` 启动成功，`http://127.0.0.1:18083/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端 clean 预览验证：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后执行 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6049`，`HTTP/1.1 200 OK`。

## 追加记录（2026-03-06 16:31）- Rerank 配置项精简

### 本次目标
- 按最新要求将本地 rerank 配置精简为仅支持：`开关 + 模型目录`。
- `rag.rerank.model-dir` 不再在 `application.yml` 中固定填写，目录来源以用户配置为主。

### 关键改动
- 前端配置页精简（`apps/desktop/src/App.vue`、`apps/desktop/src/types/index.ts`）：
  - 删除 rerank 的模型文件名、执行 Provider、CUDA 设备 ID、特征维度输入项。
  - 保留并排卡片中的“启用本地 Rerank”和“Rerank 模型目录”。
  - 保存时仅归一化 `ragRerankEnabled` 与 `ragRerankModelDir`。
  - 前端类型 `RagConfigVO/RagConfigSaveReq` 同步删减为仅两个 rerank 字段。
- 后端配置模型同步精简：
  - `RagConfigSaveReq/RagConfigVO/RagEmbeddingConfigEntity/RagConfigMapper/RagConfigServiceImpl` 去除多余 rerank 字段，仅保留 `ragRerankEnabled`、`ragRerankModelDir`。
- rerank 运行时参数来源调整（`OnnxLocalRerankServiceImpl`）：
  - 动态配置仅读取 `enabled + modelDir`。
  - 模型文件名/provider/cuda/feature-size 回退使用后端默认配置项。
- 配置文件与迁移收敛：
  - `application.yml` 删除 `rag.rerank.model-dir` 固定项。
  - `schema.sql` 与 `AiConfigMigrationRunner` 中 `rag_embedding_config` 字段定义收敛为：
    `rag_embedding_model_dir`、`rag_rerank_enabled`、`rag_rerank_model_dir`、`updated_at`。

### 规范自检（backend-api-design）
- 已按规范检查，未发现违规：
  - 接口仍为 GET/POST；
  - 请求/响应使用 DTO/VO，无 Map 直出；
  - SQL 仍在 Mapper 注解中，Service 无拼接 SQL；
  - 新增/保留字段中文注释完整。

### 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端构建：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18084` 启动成功；`http://127.0.0.1:18084/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6050`，`HTTP/1.1 200 OK`。
