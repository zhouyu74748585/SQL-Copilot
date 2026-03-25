

### 2026-03-19 23:43:13

## ????
- ??????????????????? SQL ???????????????
- ?????????????????????????????? SQLite ? Qdrant?
- ?? sql_fragment ????? managed_memory ???????????

## ????
- ???? memory_entry ??MemoryController?MemoryService?MemoryEntryMapper ??? DTO/VO?
- AiConversationContextManager ??????? session_summary????????????????
- RagRetrievalServiceImpl ????????????????sql_history ??? entry_type=history_query ??? SQL?
- QdrantClientService ???????????????????????????
- RagIngestionServiceImpl ? SQL ?????? history_id ?????? sql_fragment ????????
- ???? useMemoryModule????????????????? SQL ?????
- ?????? sqlFragmentVectorCount??? managedMemoryVectorCount?

## ????
- ???????mvn -f apps/server/pom.xml -DskipTests compile "-Dfile.encoding=UTF-8"
- ?????????mvn -f apps/server/pom.xml -DskipTests test-compile "-Dfile.encoding=UTF-8"
- ???????mvn -f apps/server/pom.xml clean package -DskipTests "-Dfile.encoding=UTF-8"
- ?? clean ?????mvn -f apps/server/pom.xml clean compile spring-boot:start "-Dspring-boot.run.arguments=--server.port=18098" "-Dfile.encoding=UTF-8"?health ?? {"code":0,"message":"success","data":"ok"}??? spring-boot:stop ??
- ?????????npm run -w @sqlcopilot/desktop type-check
- ?? clean ?????npm run -w @sqlcopilot/desktop build -- --emptyOutDir
- ?????????curl.exe -I http://127.0.0.1:55123/ ?? HTTP/1.1 200 OK

## ??
- ????????????? KV ??????????????????
- ????????????? node_modules???? npm install ??????


### 2026-03-23 17:45:43

## 20260323174100 追加记录

### 本轮目标
- 修复“历史 SQL 记忆”列表无数据但左侧统计不为 0 的问题。
- 将记忆管理左侧统计调整为全局口径，不再跟随顶部筛选条件变化。
- 让记忆管理顶部筛选框与样例 SQL 一致，去掉无法清除的 `0` 显示。

### 关键改动
- 后端 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/MemoryServiceImpl.java`
  - 为历史 SQL 记忆读取增加旧版 Qdrant payload 兼容：当 payload 缺失 `history_id` 时，按稳定 point id 回查并回填历史 ID。
  - 删除历史 SQL 记忆时新增旧版向量删除过滤条件，兼容历史 point 仅带 `session_id/sql_text/created_at` 的情况。
  - 保持历史记忆列表仍以“当前仍存在于记忆池中的向量”为准，避免已删除向量的 query_history 记录重新出现在列表里。
- 后端 `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/QdrantClientService.java`
  - 新增按 point id 批量读取 Qdrant 点位能力。
- 后端 `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/QdrantClientServiceImpl.java`
  - 实现 `POST /collections/{collection}/points` 的批量取点逻辑，供旧版 SQL 历史向量兼容读取使用。
- 前端 `apps/desktop/src/modules/studio/composables/useMemoryModule.ts`
  - 新增全局总数加载逻辑，左侧“长期记忆 / 历史 SQL 记忆”统计不再复用当前筛选列表 `total`。
  - 保留顶部筛选对列表数据的影响，但不再影响左侧全局统计。
  - 为长期记忆详情表单补充独立的目标数据库选项来源，避免复用顶部筛选数据库列表。
- 前端 `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 将记忆管理顶部筛选下拉改成与样例 SQL 相同的受控绑定方式，空值统一映射为 `undefined`，去掉无法清除的 `0`。
  - 记忆详情中的目标数据库下拉切换为表单自身连接上下文。

### 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端 clean 构建：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 Maven 验证：`mvn -f apps/server/pom.xml clean package -DskipTests -Dfile.encoding=UTF-8` 通过。
- 后端启动验证：
  - `java -Dfile.encoding=UTF-8 -jar apps/server/target/sql-copilot-server-0.1.0.jar --server.port=18113` 启动成功。
  - `GET http://127.0.0.1:18113/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - `GET /api/memory/history/page?pageNo=1&pageSize=5` 返回 `total=36` 且 `items.length=5`，列表恢复正常。
  - `GET /api/memory/history/page?pageNo=1&pageSize=3&connectionId=1&databaseName=mdm` 返回 `total=20` 且 `items.length=3`，筛选后仍能返回实际数据。
- 前端预览验证：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6069 --strictPort` 启动成功。
  - `curl -I http://127.0.0.1:6069/` 返回 `HTTP/1.1 200 OK`。

### 说明
- 本轮未重建现有 SQL 历史向量，而是通过后端兼容读取旧 point id 立即恢复历史记忆列表，避免用户手工重建。
- 左侧统计现为全局口径；顶部连接/数据库筛选仅影响当前列表内容。


### 2026-03-25 14:22:14

## 本轮目标
- 修复记忆管理中“新建记忆 / 提升为长期记忆 / 删除记忆”按钮的交互形式，改为图标按钮并通过 hover 显示含义。
- 修复历史 SQL 记忆列表缺少明显选中态的问题，避免无法判断当前操作对象。
- 修复历史 SQL 记忆提升为长期记忆后未从历史记忆池移除的问题。
- 修复表对象进入“编辑表结构”时未正确加载表结构、出现空结构的问题。
- 补齐长期记忆删除操作的显式入口。

## 关键改动
- 前端 `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 记忆管理工具栏中的“新建记忆 / 提升为长期记忆 / 删除记忆”改为图标按钮，使用 `memory.svg`、`long-term-memory.svg`、`delete.svg`，通过 tooltip 提示操作语义。
  - 长期记忆与历史 SQL 记忆卡片都增加显式选中态，当前选中的记录会高亮显示，便于判断顶部删除/提升操作作用对象。
  - 历史 SQL 记忆详情面板中移除重复的文字操作按钮，统一通过顶部图标入口执行操作。
- 前端 `apps/desktop/src/modules/studio/styles/shell.css`
  - 为记忆卡片新增 `is-active` 选中样式，并补充记忆工具栏图标按钮样式。
- 后端 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/MemoryServiceImpl.java`
  - 新增 `removeHistoryVector` 复用逻辑。
  - 历史 SQL 记忆提升为长期记忆成功后，立即同步删除对应历史向量，确保该记录不会继续出现在“历史 SQL 记忆”列表。
  - 保持旧版历史向量删除兼容逻辑，继续兼容 legacy payload / point id 场景。
- 后端 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/SchemaServiceImpl.java`
  - `getTableDetail` 增加大小写无关的表名匹配与真实表名回填。
  - 读取列、索引、MySQL 扩展信息时统一使用匹配到的真实表名，避免编辑表结构时返回空 columns/indexes。

## 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 Maven 验证：`mvn -f apps/server/pom.xml clean package -DskipTests -Dfile.encoding=UTF-8` 通过。
- 后端启动验证：
  - `java -Dfile.encoding=UTF-8 -jar apps/server/target/sql-copilot-server-0.1.0.jar --server.port=18131` 启动成功。
  - `GET http://127.0.0.1:18131/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览验证：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6072 --strictPort` 启动成功。
  - `curl -I http://127.0.0.1:6072/` 返回 `HTTP/1.1 200 OK`。
- 只读接口补充验证：
  - `GET /api/schema/tableDetail?connectionId=1&databaseName=mdm&tableName=distribution_callback_log` 返回完整列与索引。
  - `GET /api/schema/tableDetail?connectionId=1&databaseName=mdm&tableName=DISTRIBUTION_CALLBACK_LOG` 也能返回同一表结构，确认大小写无关匹配生效。

## 说明
- 本轮没有直接对现有记忆数据执行“提升/删除”写操作验证，避免修改用户现场数据；相关行为已通过代码链路修复并完成编译、启动与只读接口校验。
