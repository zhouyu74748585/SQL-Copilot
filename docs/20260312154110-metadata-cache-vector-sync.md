# 主题：metadata-cache-vector-sync

## 记录

### 2026-03-12 15:41:10

## 2026-03-12 15:40 元数据缓存与向量同步

- 目标：数据库元数据发生删除、重命名、建表、改表、删连接等变化后，及时刷新 Schema 相关缓存，并同步删除或重建对应向量化数据，避免前端树、对象列表、RAG 数据出现脏读。

### 后端改动
- 新增 `MetadataChangeSyncService` / `MetadataChangeSyncServiceImpl`，统一承接连接删除、库新建/重命名/删除、表新建/改表/重命名/删除，以及对象定义保存/删除后的缓存刷新与向量同步动作。
- `ConnectionController` 在删除连接后触发连接级清理；`SchemaController` 在命名空间、表、对象定义相关操作成功后触发对应同步流程，避免零散控制器各自维护刷新逻辑。
- `SchemaService` / `SchemaServiceImpl` 增加 `refreshConnectionSchemaCaches`，支持连接级清空数据库列表、Schema 快照、表统计等缓存。
- `TableOperationVO` 增加 `databaseName`、`tableName`，`SchemaServiceImpl` 在建表、改表、删表、清空表后补齐上下文，便于控制器按真实对象执行后续同步。
- `RagVectorizeQueueService` / `RagVectorizeQueueServiceImpl` 增加库级、连接级状态清理能力，删除或重命名时会移除排队任务、抑制运行中任务并清理 `rag_vectorize_status` 状态。
- `RagIngestionService` / `RagIngestionServiceImpl` 增加表级、库级、连接级向量工件删除：
  - 删除表时清理 schema 向量以及命中该表的 SQL history / SQL fragment 向量；
  - 删除库时按 `connection_id + database_name` 清理 schema/history/fragment 数据；
  - 删除连接时按 `connection_id` 整体清理。
- `ingestSchema()` 调整为每次入库前先删除对应库的旧 schema 向量；即使当前库已无表，也会完成清理，避免删表或清库后残留旧向量。
- `RagVectorizeStatusMapper` 增加按库、按连接删除状态记录的 SQL。

### 前端改动
- `useStudioRuntime.ts` 新增：
  - `invalidateConnectionMetadataCaches`
  - `invalidateDatabaseMetadataCaches`
  - `invalidateDatabaseListCache`
  - `handleDatabaseRenamedLocally`
- 连接删除、库新建/重命名/删除、表重命名、表编辑执行、对象定义保存、对象删除、表复制完成后，统一调用上述 helper 失效本地缓存，再刷新左树或当前对象列表。
- 重点修复 `databaseListCache` 未失效时 `prepareConnectionTreeData()` 复用旧列表的问题，避免删库/改库名后左侧树仍显示旧数据库。
- 顺手修复两个前端类型问题：
  - `useConnectionBrowserModule.ts` 上下文菜单分支改为显式 `ContextMenuActionItem[]`，避免 TS 推断过宽；
  - `useTableEditorModule.ts` 补回 `refreshSchemaMetadata` 薄封装，兼容 `StudioShell.vue` 现有导出引用。

### 验证
- `mvn -f apps/server/pom.xml clean package`
  - 仍被仓库已有失败测试阻塞：
    - `AiServiceImplAstValidationTest.buildRepairPrompt_keepsOnlyDynamicRepairContext`
    - `OnnxLocalRerankServiceImplTest.score_acceptsCrossEncoderModelInputs`
- `mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- `npm run type-check` 通过。
- `npm run build -- --emptyOutDir` 通过。
- `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18090"` 启动成功，`http://127.0.0.1:18090/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6064 --strictPort` 启动成功，`http://127.0.0.1:6064/` 返回 `HTTP/1.1 200 OK`。


### 2026-03-12 16:12:57

## 2026-03-12 单表变更避免整库重向量化

### 本次目标
- 修正单表复制完成后仍触发整库重新向量化的问题。
- 保证单表变更只做目标表的向量同步，不扩大到整个数据库。

### 关键改动
- `TableCopyServiceImpl` 在表复制成功后的收尾阶段，不再调用库级 `ragVectorizeQueueService.enqueue(connectionId, databaseName)`。
- 新增 `triggerTargetTableVectorize()`，复制成功后仅对目标表调用 `ragVectorizeQueueService.vectorizeTable(connectionId, databaseName, targetTableName)`。
- 单表向量化失败时只记录警告日志，不影响已成功完成的表复制结果，避免将向量化异常放大成复制失败。

### 验证结果
- 后端构建：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18092"` 成功。
- 健康检查：`curl --noproxy '*' http://127.0.0.1:18092/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6066 --strictPort` 成功，`curl --noproxy '*' -I http://127.0.0.1:6066/` 返回 `HTTP/1.1 200 OK`。

### 说明
- 本次未改动前端业务代码，因此未额外执行前端 type-check/build。
