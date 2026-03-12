# 主题：table-copy-jdbc-introspection

## 记录

### 2026-03-12 11:55:50

## 本次目标
- 调整复制表逻辑，优先直接从源数据库读取建表 DDL，而不是在 Java 中手工拼接。
- 将 SchemaServiceImpl 中多数据库 Schema 读取改为优先使用 `jdbc-drivers.yml` 中的 introspection SQL，减少代码中的数据库差异硬编码。

## 关键改动
- 扩展 `JdbcDriverResolver`：新增 introspection SQL 与复制建表 DDL 配置解析能力，统一从 `jdbc-drivers.yml` 暴露数据库元数据查询配置。
- 调整 `SchemaServiceImpl`：
  - `listDatabases()` 改为优先执行 yml 中的 `schemas` 查询。
  - `loadSnapshot()` / `loadColumnsForTable()` 改为优先执行 yml 中的 `tables` / `columns` / `primaryKeys` 查询。
  - 保留 JDBC Metadata 作为缺失配置时的降级路径，避免已有能力回退。
- 调整 `TableCopyServiceImpl`：
  - 新增“源库 DDL 直取”链路，优先按 yml 配置读取源表建表语句并改写目标表名。
  - 无可用 DDL 配置或读取失败时，再回退到原来的结构重建逻辑。
- 更新 `jdbc-drivers.yml`：
  - 补充 PostgreSQL / MySQL / SQL Server 的列默认值、自增、注释等字段。
  - 为 MySQL 配置 `show create table` 复制建表语句。
  - 为 PostgreSQL 配置基于系统 catalog 的建表 DDL 生成 SQL。

## 验证结果
- 后端打包：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 后端 clean 启动：
  - 默认端口 `18080` 被环境中已有进程占用，非本次改动导致。
  - 改用 `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18086"` 启动成功。
  - `curl --noproxy '*' http://127.0.0.1:18086/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6060 --strictPort` 启动成功。
  - `curl --noproxy '*' -I http://127.0.0.1:6060/` 返回 `HTTP/1.1 200 OK`。

## 备注
- 本次改动仅涉及后端与 `jdbc-drivers.yml`，未改动前端业务代码。


### 2026-03-12 12:59:10

## 2026-03-12 复制表优化与对象浏览补齐

### 本次目标
- 重做表复制执行策略，补齐同连接 fast path、跨连接流式复制和两阶段失败保表语义。
- 新增表重命名接口与前端交互，并让左侧树表节点与右侧列表表项具备一致的右键菜单和双击行为。

### 关键改动
- 后端 `TableCopyServiceImpl` 调整为按同连接/跨连接分流：
  - 同连接优先读取 `jdbc-drivers.yml` 中的 `tableCopy.fastPath` 模板执行快速复制。
  - 跨连接结构+数据复制改为 JDBC 流式游标读取，`fetchSize=1000`，目标端批量写入大小 `1000`。
  - 跨连接数据复制失败时仅回滚当前批次，保留已创建的目标表，不再自动删表。
- 后端新增 `POST /api/schema/table/rename`，新增 `TableRenameReq` / `TableRenameVO`。
- `JdbcDriverResolver` 与 `jdbc-drivers.yml` 扩展了：
  - `tableCopy.structureOnlySameDatabaseSql`
  - `tableCopy.structureAndDataSameDatabaseSql`
  - `tableCopy.structureOnlyCrossDatabaseSql`
  - `tableCopy.structureAndDataCrossDatabaseSql`
  - `tableOperations.renameTableSql`
- `SchemaServiceImpl` 新增基于 `jdbc-drivers.yml` 的表重命名执行逻辑。
- 前端新增 `useTableRenameModule.ts`，完成重命名弹窗、接口调用、树/列表/已打开表结构页签/数据页签/剪贴板状态同步更新。
- `useConnectionBrowserModule.ts` 增加 `renameTable` 上下文动作与树节点双击处理，左树表节点与右侧列表表项统一复用同一套对象操作菜单。
- `StudioShell.vue` 增加表重命名右键菜单入口、重命名弹窗，并让树节点双击直接打开数据浏览。

### 验证结果
- 后端构建：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 前端类型检查：`npm run type-check` 通过。
- 前端构建：`npm run build -- --emptyOutDir` 通过。
- 后端启动：默认端口 `18080` 已被现有进程占用，改用 `18086` 执行 `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18086"`，启动成功。
- 健康检查：`http://127.0.0.1:18086/api/health` 返回 `ok`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6060 --strictPort` 启动成功，`http://127.0.0.1:6060/` 返回 `HTTP 200`。

### 说明
- 启动验证完成后，已回收本轮临时启动的后端与前端预览进程。
