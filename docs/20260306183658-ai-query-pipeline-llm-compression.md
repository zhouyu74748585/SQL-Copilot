# 主题：ai-query-pipeline-llm-compression

## 记录

### 2026-03-06 18:36:58

## 追加记录（2026-03-06）- AI查询链路与LLM压缩重构

### 本次目标
- 重构 AI 查询链路，覆盖 auto 与单能力接口（generate/explain/analyze/generate-chart）。
- 将会话上下文压缩从字符串截断改为 LLM 压缩。
- 落地 Explain/Analyze 共用的 SQL 提取/解析/精确元数据流程。
- 将成功 SQL 的 history_query 向量化改为异步队列，并补充语义描述与业务标签。

### 关键改动
- 后端 `AiServiceImpl`：
  - 新增两阶段意图识别：
    - 关闭记忆时：直接最终意图识别。
    - 开启记忆时：轻量意图识别（含检索参数） -> 检索会话/全局历史 -> 最终意图识别。
  - Explain/Analyze 改为共用 SQL 理解管线：
    - LLM 结构化 SQL 提取契约：`{has_sql, sql_list}`。
    - JSqlParser 解析 SQL（表、列、聚合、where/group/order/join）。
    - 按解析表精确读取字段元数据；Analyze 额外携带 PK/索引信息。
    - 在“解析失败/元数据覆盖不足/多 SQL 歧义”时触发补充召回（example_sql/metric/history）。
    - Analyze 使用提取出的 SQL 获取执行计划上下文。
  - 图表链路新增 SQL AST 校验，与生成 SQL 保持一致的校验强度。
  - 会话压缩链路移除 `cutText`：
    - 历史摘要改为 LLM 压缩。
    - 窗口结构化上下文保留原文 JSON，不做截断。
    - 向量记忆召回文本注入前增加 LLM 归并压缩。
  - 增加请求内 LLM 缓存（ThreadLocal + depth），避免同请求重复调用压缩/提取。
  - 新增原始提示词调用通道（raw provider），用于意图识别/SQL提取/上下文压缩等结构化任务，避免递归拼装上下文。

- 后端 `RagIngestionServiceImpl`：
  - SQL 历史入库改为异步单线程队列处理。
  - 成功 SQL 入库条目标记 `entry_type=history_query`（集合仍用 `sql_history`）。
  - 入库链路新增：
    - SQL 解析特征 -> 精确元数据读取（含索引/主键） -> LLM 语义描述与业务标签（失败规则降级） -> 向量 metadata 写入。
  - SQL 历史 payload/document 增加：
    - `semantic_description`
    - `business_tags`（metrics/dimensions/time_semantics/chart_types/calibers/topics）
    - 拆分扁平标签字段，便于检索过滤/重排使用。

### 验证结果
- 后端编译：`mvn -f apps/server/pom.xml -DskipTests compile` 通过。
- 后端打包：`mvn -f apps/server/pom.xml clean package` 通过（含测试 1/1 通过）。
- 前端校验：
  - `npm install`（补齐依赖）
  - `npm run -w @sqlcopilot/desktop type-check` 通过
  - `npm run -w @sqlcopilot/desktop build` 通过
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18090`，健康检查 `http://127.0.0.1:18090/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后执行 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6055`，`HTTP/1.1 200 OK`。

### 备注
- 本次仅改后端服务实现与向量入库行为，不变更外部 HTTP API 路径与主 DTO/VO 结构。
- 保持 UTF-8 编码。

### 2026-03-08 19:30:00

## Additional Record: unified llm gateway and ai trace output

### Scope
- Unified backend llm entry by `modelId`; callers no longer branch by OpenAI vs CLI.
- Added trace output for `/api/ai/query/*`: `generate`, `auto`, `explain`, `analyze`, `generate-chart`, `repair`.
- Added persisted detail-output switch in AI config and tab-level override in query workspace.
- Added `trace_json` persistence for query history and restored trace playback from history.

### Backend
- Added `LlmGatewayService`, `LlmGatewayRequest`, `LlmGatewayResult` and routed provider selection by `modelId -> model option -> providerType`.
- Updated `AiServiceImpl` to use the shared gateway for SQL/text/raw-text tasks and to attach explicit trace stages plus llm call payloads.
- Updated `RagIngestionServiceImpl` to reuse the shared gateway instead of bypassing with direct OpenAI wiring.
- Added `AiTraceVO`, `AiTraceStageVO`, `AiTraceFieldVO`, `AiTraceLlmCallVO` and exposed `trace` on query response DTOs.
- Added `detailOutputEnabled` to AI config DTO/entity/mapper flow.
- Added `trace_json` to query history entity/mapper/schema/save/load flow.

### Frontend
- Added shared trace contracts in `packages/shared-contracts` and desktop local types.
- Updated studio runtime request payloads to send `modelId` and `detailOutputEnabled`.
- Added assistant trace rendering block in chat cards with collapse/expand behavior.
- Added global detail-output switch in AI settings and tab-level override in query UI.
- Added trace persistence and replay when opening query history sessions.

### Validation
- Backend compile: `mvn -f apps/server/pom.xml clean compile -DskipTests` passed.
- Backend startup: `mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18087` passed health check at `http://127.0.0.1:18087/api/health` and returned `{"code":0,"message":"success","data":"ok"}`.
- Frontend type-check: `npm run -w @sqlcopilot/desktop type-check` passed.
- Frontend build: `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` passed.
- Frontend preview: `npx vite preview --host=127.0.0.1 --port=14173 --strictPort` returned HTTP 200.

### Note
- During implementation, `AiServiceImpl.java` had broken string literals from prior edits. The file was repaired and validated with a clean backend compile before final startup verification.

### 2026-03-08 21:40:00

## Additional Record: auto-mode trace aggregation fix

### Scope
- Fixed missing trace output in `/api/ai/query/auto`.
- Ensured both routed child responses and intent-identify fallback can return trace payloads.

### Backend
- Updated `AiServiceImpl.autoQuery` to merge the `identify_intent` stage with delegated trace stages returned by `generateSql` / `explainSql` / `analyzeSql` / `generateChart`.
- Fixed the `GENERATE_CHART` branch to forward `chart.getTrace()` into the auto response.
- Added fallback trace generation for `identifyIntent` failure so auto mode still shows at least one trace stage when it degrades to clarify content.

### Validation
- Backend compile: `mvn -f apps/server/pom.xml clean compile -DskipTests` passed.
- Backend startup: `mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18191` passed health check at `http://127.0.0.1:18191/api/health` and returned `{"code":0,"message":"success","data":"ok"}`.
- Frontend type-check: `npm run -w @sqlcopilot/desktop type-check` passed.
- Frontend clean build: `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` passed.
- Frontend preview: `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 14191 --strictPort` returned HTTP 200.
- Auto API spot check: `POST /api/ai/query/auto` with `detailOutputEnabled=true` returned `trace != null` and `traceStageCount = 1` on the local fallback path.

### Note
- The local auto spot check hit the intent-fallback path in the current environment, so the runtime verification confirmed fallback trace presence directly; routed success-path aggregation was validated by code path inspection plus clean compile/startup.
