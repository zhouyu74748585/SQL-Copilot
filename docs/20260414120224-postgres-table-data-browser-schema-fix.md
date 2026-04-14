# 主题：postgres-table-data-browser-schema-fix

## 记录

### 2026-04-14 12:02:24

## 本次目标
- 修复 PostgreSQL 数据浏览分页时因 relation 不存在导致的分页查询失败。
- 保证数据浏览相关的增删改 SQL 与分页查询在 schema 上下文下引用同一目标表。

## 关键改动
- 更新 `apps/server/src/main/java/com/sqlcopilot/studio/repository/TableDataJdbcRepository.java`。
- 数据浏览分页、删除、更新、新增 SQL 统一改为通过 `database::schema` 上下文生成限定表名；在 PostgreSQL / SQLServer / Oracle 下优先使用 namespace（schema）前缀，在 MySQL 下保留 database 前缀能力。
- 补充标识符转义，避免双引号、反引号、方括号等特殊字符导致引用异常。
- 更新 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/TableDataServiceImpl.java`，将当前数据浏览上下文透传给仓储，保证分页与提交都使用同一限定表名策略。

## 验证结果
- 后端 clean 启动通过：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081'`
- 健康检查通过：`curl http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
- 前端连通性通过：`curl -I http://127.0.0.1:4173` 返回 `HTTP/1.1 200 OK`。
- 后端 `mvn -f apps/server/pom.xml clean package` 未通过，失败点为既有测试 `RagRetrievalServiceImplTest.retrievePromptContext_supplementsExplicitFocusTableAndUsesCompactQuery` 断言与当前实现不一致；与本次 PostgreSQL 分页修复无直接关联。


### 2026-04-14 13:38:33

## 追加修复
- 兼容数据浏览请求中的表名已包含 schema 前缀的场景，例如 `public.conversations`。
- `TableDataJdbcRepository` 在生成表引用时会优先拆分 `schema.table`，分别转义后再拼接，避免 PostgreSQL 将 `"public.conversations"` 识别为单个 relation 名称。

## 追加验证结果
- 后端快速编译通过：`mvn -f apps/server/pom.xml -DskipTests compile`
- 后端 clean 启动通过：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081'`
- 健康检查通过：`curl http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
- 前端连通性通过：`curl -I http://127.0.0.1:4173` 返回 `HTTP/1.1 200 OK`。


### 2026-04-14 13:52:42

## 追加修复：PostgreSQL 数据浏览目标库建连
- 根因确认：数据浏览分页与提交仍使用 `openTargetConnection(connectionId)`，未像 Schema 浏览那样传入目标 `databaseName`。
- 在 PostgreSQL 下，`setCatalog()` 不能替代真正的跨库建连；如果 JDBC 连接仍停留在默认数据库，即使 SQL 为 `"public"."checkpoint_blobs"` 也会报 relation 不存在。
- 已将 `TableDataServiceImpl` 的分页与提交入口统一改为 `openTargetConnection(connectionId, databaseName)`，与 SchemaService 的目标库建连策略保持一致。

## 追加验证结果
- 后端快速编译通过：`mvn -f apps/server/pom.xml -DskipTests compile`
- 后端 clean 启动通过：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081'`
- 健康检查通过：`curl http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
- 前端连通性通过：`curl -I http://127.0.0.1:4173` 返回 `HTTP/1.1 200 OK`。
