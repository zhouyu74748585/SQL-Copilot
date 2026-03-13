# 主题：sql-vector-recall-example-table-supplement

## 记录

### 2026-03-09 12:57:32

## 20260309125730 追加记录

### 本次目标
- 在 SQL 生成链路中，当向量召回命中样例 SQL 但召回表未覆盖样例 SQL 关联表时，自动补齐缺失表与字段元数据。
- 补齐后的表/字段召回项需要在排序中置顶，确保优先进入 RAG 上下文。

### 关键改动
- 修改文件：`apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagRetrievalServiceImpl.java`
  - 注入 `SchemaService`，用于按表名回查 Schema 概览与表详情。
  - 新增样例 SQL 关联表提取逻辑：优先读取 payload 中 `tables`，若缺失则从 `sql_text` 解析 `FROM/JOIN/UPDATE/INTO/TABLE` 关联表。
  - 在样例 SQL rerank 后新增补全流程：
    - 找出“样例 SQL 关联表 - 已召回表”的差集。
    - 基于 `schemaService.getOverview/getTableDetail` 组装缺失表的表元数据（表名、备注、字段预览）并补入 table hits。
    - 组装缺失表字段元数据（字段名、类型、备注、索引/主键信息）并补入 column hits。
    - 对补齐项分配高于当前最大分数的 boost 分值，并按分数倒序重排，确保补齐项位于前列。
  - 调整表约束过滤行为：对 `exampleSqlHits` 不再执行基于 `tableConstraints` 的裁剪，避免样例 SQL 在补全前被提前过滤。
  - 增加补全过程日志：`[RAG-RETRIEVE-EXAMPLE-SUPPLEMENT]`。

### 验证结果
- 后端 Maven 打包（clean）通过：
  - `mvn -f apps/server/pom.xml clean package`。
- 后端启动验证（clean）通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18087"`。
  - 健康检查：`curl --noproxy '*' http://127.0.0.1:18087/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览验证（clean）通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`。
  - `npx vite preview --host 127.0.0.1 --port 55061 --strictPort`。
  - 探活：`curl --noproxy '*' -I http://127.0.0.1:55061/` 返回 `HTTP/1.1 200 OK`。


### 2026-03-09 13:11:39

## 20260309131215 追加记录

### 本次目标
- 在样例 SQL 向量化保存时，将 SQL 解析得到的关联表写入 payload 的 `tables` 字段，便于后续召回链路直接使用。

### 关键改动
- 修改文件：`apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagIngestionServiceImpl.java`
  - 在 `buildKnowledgeExamplePayload()` 中复用 `extractSqlFeatureMeta(entity.getSqlText())`。
  - 将解析结果 `featureMeta.getTables()` 写入 payload：`tables`。
  - 保持原有字段（`knowledge_id/scope/connection_id/database_name/sql_text/sql_semantic/term_ids_json`）不变。

### 验证结果
- 后端 Maven 打包（clean）通过：
  - `mvn -f apps/server/pom.xml clean package`。
- 后端启动验证（clean）通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18087"`。
  - 健康检查：`curl --noproxy '*' http://127.0.0.1:18087/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览验证（clean）通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`。
  - `npx vite preview --host 127.0.0.1 --port 55061 --strictPort`。
  - 探活：`curl --noproxy '*' -I http://127.0.0.1:55061/` 返回 `HTTP/1.1 200 OK`。
### 2026-03-11 23:45:00

## 20260311234500 追加记录

### 本次目标
- 优化 SQL 生成链路的 RAG 检索输入与重点表召回，降低冗长上下文对表名信号的稀释。
- 修复本地 ONNX rerank 将 `float` 特征误喂给 cross-encoder 模型导致的 `tensor(int64)` 输入类型异常。

### 关键改动
- 修改 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/AiServiceImpl.java`
  - 检索 hint 收敛为仅保留 `检索关键词 + 重点表`，不再向 RAG 检索文本注入 `意图依据/意图类型/置信度`。
- 修改 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/AiConversationContextManager.java`
  - `buildRetrievalInputForRag(...)` 新增紧凑 hint 识别。
  - 当上游传入 `检索关键词/重点表` 时，RAG 输入优先使用紧凑 hint，而不是继续把原始 prompt 与冗余上下文整体拼接。
- 修改 `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagRetrievalServiceImpl.java`
  - 新增检索输入解析，提取 `检索关键词` 与 `重点表`，并用更紧凑的 embedding 文本参与向量检索。
  - 新增基于显式重点表的 schema 补召回：当首轮 `tableHits` 未命中用户点名表时，直接从 `SchemaService` 补表与字段元数据，并提升排序分数。
  - 新增基于显式重点表的历史 SQL 补召回：按 `tables=focusTable` 从 `sql_history` 中补入相关历史样例。
  - 将 `tableConstraints` 过滤后移到补召回与 rerank 之后，避免首轮表召回错误时过早裁掉正确的列/历史候选。
  - 强化规则分：增加重点表精确匹配、payload 表名命中用户文本的 bonus，在本地 rerank 缺失时也能更稳定地把显式表名顶上来。
- 修改 `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/OnnxLocalRerankServiceImpl.java`
  - 将本地 rerank 从手工 `float[feature]` 输入改为真实 cross-encoder 推理。
  - 新增 `tokenizer.json` 加载与 query-document pair 编码，按 `int64 input_ids/attention_mask(/token_type_ids)` 构造 ONNX 输入。
  - 支持按 bucket 组装 table/column/history/example 文档文本。
  - 修复 `ORT_INVALID_ARGUMENT: Unexpected input data type. Actual tensor(float), expected tensor(int64)`。

### 测试补充
- 新增 `apps/server/src/test/java/com/sqlcopilot/studio/service/impl/AiConversationContextManagerRetrievalInputTest.java`
  - 验证紧凑检索 hint 会覆盖冗长 prompt 拼接。
- 新增 `apps/server/src/test/java/com/sqlcopilot/studio/service/rag/impl/RagRetrievalServiceImplTest.java`
  - 验证显式重点表会触发 schema/history 补召回，并且 embedding 输入不再包含 `意图类型` 等冗余字段。
- 新增 `apps/server/src/test/java/com/sqlcopilot/studio/service/rag/impl/OnnxLocalRerankServiceImplTest.java`
  - 直接加载本地 `BgeRerankerBaseOnnxO4` 模型，验证 rerank 可以正常输出分数，不再触发 `tensor(float)`/`tensor(int64)` 类型异常。

### 验证结果
- 后端测试（clean）通过：
  - `mvn -f apps/server/pom.xml clean test`
- 后端启动验证（clean）通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18091"`
  - 健康检查：`http://127.0.0.1:18091/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端预览验证（clean）通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6052 --strictPort`
  - 探活：`http://127.0.0.1:6052/` 返回 `HTTP 200`
### 2026-03-12 00:00:00

## 20260312000000 追加记录

### 本次目标
- 修正 RAG trace 中 `rerankProvider` 在首轮请求里因懒加载而显示 `LOCAL_ONNX_UNAVAILABLE` 的时机问题。

### 关键改动
- 修改 `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagRetrievalServiceImpl.java`
  - `retrievePromptContext(...)` 入口仍记录初始 provider，但不再把它直接作为最终 trace 结果。
  - `rerankHits(...)` 在 `ragRerankService.score(...)` 执行后重新读取 runtime provider，bucket 级 trace 记录真实初始化后的 provider。
  - 所有 bucket rerank 完成后，再回填 `RagPromptContext.rerankProvider`，保证首次请求也能显示 `LOCAL_ONNX_CPUEXECUTIONPROVIDER` 这类真实 provider。
  - 新增 `currentRerankProvider(...)` 统一处理 `DISABLED` 与 runtime provider 的读取回退。
- 修改 `apps/server/src/test/java/com/sqlcopilot/studio/service/rag/impl/RagRetrievalServiceImplTest.java`
  - 新增首轮请求测试：先返回 `LOCAL_ONNX_UNAVAILABLE`，再在 `score(...)` 后返回 `LOCAL_ONNX_CPUEXECUTIONPROVIDER`，验证 context 与 table bucket trace 都使用最终 provider。

### 验证结果
- 后端测试通过：
  - `mvn -f apps/server/pom.xml -Dtest=RagRetrievalServiceImplTest test`

### 2026-03-12 00:05:00

## 20260312000500 追加记录

### 本次目标
- 去重 SQL 修复链路中的提示词定义，避免 `buildRepairPrompt(...)` 与 `REPAIR_SQL_SYSTEM_PROMPT` 重复维护同一套约束。

### 关键改动
- 修改 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/AiServiceImpl.java`
  - 收缩 `buildRepairPrompt(...)`，只保留本次修复请求的动态输入：
    - `Execution error`
    - `Original SQL`
  - 移除重复的静态说明：
    - 修复任务描述
    - 保持业务意图不变
    - 严格 JSON 输出要求
    - `errorExplanation` / `repairedSql` 字段声明
  - 上述稳定约束统一只由 `REPAIR_SQL_SYSTEM_PROMPT` 承担。
- 修改 `apps/server/src/test/java/com/sqlcopilot/studio/service/impl/AiServiceImplAstValidationTest.java`
  - 新增 `buildRepairPrompt_keepsOnlyDynamicRepairContext()`，校验修复 prompt 仅包含动态上下文，不再重复系统提示中的 JSON 约束。

### 验证结果
- 后端测试通过：
  - `mvn -f apps/server/pom.xml -Dtest=AiServiceImplAstValidationTest test`
- 前端 clean 构建通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动已尝试：
  - `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18103"`
  - 当前会话执行超时，未拿到健康检查结果；本次修改仅涉及 prompt 文本拼装与测试，不涉及启动链路代码。


### 2026-03-13 10:58:45

## 追加记录（2026-03-13）- RAG 召回与 Prompt 重构

### 本次目标
- 落地“schema 真值优先、样例 SQL 条件参考、先锚定再扩展、预算化 prompt”这一轮 RAG 重构。
- 修复知识中心术语/样例 SQL 的结构化元数据生产与消费不一致问题。
- 为后续离线评测和召回调参补齐 trace、门控和测试基线。

### 关键改动
- 知识元数据建模与落库
  - 新增 `KnowledgeMetadataUtil`，统一派生术语与样例 SQL 的结构化元数据。
  - `knowledge_term` 扩展为：`aliases_json`、`metric_expression`、`related_tables_json`、`related_columns_json`、`term_type`。
  - `knowledge_example_sql` 扩展为：`question_text`、`question_variants_json`、`semantic_summary`、`normalized_sql`、`sql_template`、`sql_ast_json`、`table_names_json`、`column_names_json`、`metric_tags_json`、`time_tags_json`、`verified_flag`、`quality_score`、`source_type`、`sql_operation_type`。
  - `schema.sql`、Mapper、实体、`KnowledgeServiceImpl`、`EditorKnowledgeSchemaMigrationRunner` 同步更新，允许直接重建/升级本地 SQLite 结构。
- 向量入库重构
  - `RagIngestionServiceImpl` 的表文档不再塞 30 个字段预览，改为主键/索引/时间/度量/维度等角色摘要。
  - schema table/column、knowledge term/example、query history payload 统一增加 `entity_id/entity_type/trust_level/entity_version/sql_operation_type` 等路由字段。
  - SQL history 文档增强为“问题 + SQL + 操作类型 + 可信度 + 来源类型 + 语义标签”。
- 检索链路重构
  - `RagRetrievalServiceImpl` 增加锚点表选择、表内字段二次筛选、样例 SQL 强门控、预算化 prompt 组装。
  - 最终 prompt 固定为 5 段：`用户目标与硬约束`、`确认的表锚点`、`相关字段`、`口径与术语`、`参考 SQL`。
  - 参考 SQL 改为“可参考但不可直接照搬”，并在 prompt 中显式声明以当前 schema/术语为准。
  - `RagPromptContext` 新增 `selectionDetails`、`promptBudgetUsed`，trace 能看到 anchor filter、example gate、prompt assembly 的决策细节。
  - 新增样例 SQL 门控规则：质量分、作用域、SQL 操作类型、表重叠、验证状态共同决定是否进入 prompt。
- Rerank 文档重构
  - `OnnxLocalRerankServiceImpl` 与 `OpenAiCompatRerankServiceImpl` 改为按 bucket 输出更完整的结构化文档，不再只看旧字段。
  - 表桶包含主键/索引/时间/度量/维度；列桶包含字段角色；术语桶包含 aliases/related tables；样例/历史桶包含 question/semantic/sql template/operation/trust 等信息。
- Prompt 与测试修正
  - `AiServiceImpl` 删除“优先参考样例 SQL”的强指令，改为“样例 SQL 仅作参考，冲突时以 schema/术语为准”。
  - 生成提示词去掉重复拼接的关联索引块，避免和新的 RAG prompt 重复稀释用户问题。
  - 修复 `buildRepairPrompt`，仅保留动态修复上下文。
  - 新增回归测试：错误样例 SQL 因与锚点表无重叠被门控丢弃，且 trace/prompt budget 会输出。
  - ONNX rerank 测试在本地模型缺失时改为跳过，避免环境差异导致构建失败。

### 验证结果
- 后端 Maven：`mvn -f apps/server/pom.xml clean package` 成功。
- 后端单测：10 个测试通过，1 个测试因本地缺少 `models/BgeRerankerBaseOnnxO4` 被跳过。
- 后端启动：
  - 使用 `java -jar apps/server/target/sql-copilot-server-0.1.0.jar --server.port=18081 --spring.datasource.url=jdbc:sqlite:/tmp/sql-copilot-validation-03f3.db` 启动成功。
  - 由于本机已有 Java 进程占用默认 `18080`，本次验证改用 `18081` 独立端口；Tomcat 成功监听且 HTTP 返回 200。
- 前端验证：
  - 先执行 `npm ci` 补齐当前工作树依赖。
  - `npm run build` 成功。
  - `npm run type-check` 成功。
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173` 启动成功，`curl -I http://127.0.0.1:4173/` 返回 200。

### 遗留项
- 当前 bucket-specific rerank 已完成文档输入和规则分增强，但 `alpha/beta/gamma` 仍是全局参数，后续可继续按 bucket 拆分配置。
- query history 侧虽然补了 `trust_level/source_type/reuse_count/sql_operation_type`，但还没有完整的离线评测集与自动调参闭环。
- `sql_fragment` 仍保留旧集合，后续可以继续评估是否改造成 AST pattern 文档或直接下线。
