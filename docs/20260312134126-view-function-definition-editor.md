# 主题：view-function-definition-editor

## 记录

### 2026-03-12 13:41:26

## 2026-03-12 视图/函数定义编辑与视图数据浏览

### 本次目标
- 补齐对象浏览中视图和函数的 SQL 定义编辑能力。
- 让视图复用现有数据浏览页并保持只读。
- 统一左树和右侧列表对视图/函数的右键菜单和双击行为。

### 关键改动
- 后端新增对象定义接口：
  - `GET /api/schema/object/definition`
  - `POST /api/schema/object/definition/save`
- 新增 DTO/VO：
  - `SchemaObjectDefinitionVO`
  - `SchemaObjectDefinitionSaveReq`
  - `SchemaObjectDefinitionSaveVO`
- 新增 `SchemaObjectDefinitionJdbcRepository`，把视图/函数定义读取与保存的动态 SQL 统一集中到仓储实现。
- `JdbcDriverResolver` 增加 `objectDefinitions` 配置解析，`jdbc-drivers.yml` 为 MySQL、PostgreSQL、SQL Server 补齐了视图/函数的 `fetchSql`、`saveStrategy`、`replaceSql`、`dropSql` 配置。
- `SchemaService` / `SchemaServiceImpl` 增加视图/函数定义读取与保存逻辑，并在保存后继续走 schema cache 刷新与重新向量化。
- 表数据接口扩展 `objectType`：
  - `TableDataPageReq.objectType`
  - `TableDataCommitReq.objectType`
- `TableDataServiceImpl` 现在支持 `views` 分页浏览，并固定返回只读原因“视图只支持只读浏览”；提交接口若传 `views` 会直接拒绝。
- 前端新增 `useObjectDefinitionEditorModule.ts`，增加独立的对象定义编辑页签，支持打开、保存、刷新、复制，并复用现有 Monaco SQL 补全能力。
- `useConnectionBrowserModule.ts` 现在按对象类型分流：
  - 表：保留原有操作
  - 视图：SQL查询、数据浏览、编辑定义
  - 函数：编辑定义
- `StudioShell.vue` 新增对象定义编辑页签展示区；视图双击打开数据浏览，函数双击打开定义编辑页。
- `useTableDataModule.ts` 与运行时页签状态扩展为支持 `tables/views` 两类对象数据页。

### 验证结果
- 后端构建：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 前端类型检查：`npm run type-check` 通过。
- 前端构建：`npm run build -- --emptyOutDir` 通过。
- 后端启动：默认端口 `18080` 已被现有进程占用，改用 `18086` 执行 `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18086"`，启动成功。
- 健康检查：`http://127.0.0.1:18086/api/health` 返回 `ok`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6060 --strictPort` 启动成功，`http://127.0.0.1:6060/` 返回 `HTTP 200`。

### 说明
- 当前“函数”仍按现有对象树中的 `functions` 对象处理，不扩展到 `procedures`。
- 若用户在定义编辑页直接改对象名，后端会阻止保存，不支持通过定义页改名。
