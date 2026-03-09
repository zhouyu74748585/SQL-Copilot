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
