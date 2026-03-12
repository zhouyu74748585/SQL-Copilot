# 主题：navigation-tree-context-actions

## 记录

### 2026-03-12 15:01:51

## 本次目标
- 为左侧导航树补齐按节点类型与数据库类型能力动态生成的右键菜单。
- 覆盖连接、库/Schema、分组节点、表、视图、函数、已保存查询的新增/编辑/删除能力。
- 保持现有树层级不变，并完成前后端启动验证。

## 关键改动
- 后端扩展 `GET /api/connection/db-types` 返回数据库类型能力声明：新增命名空间标签、namespace create/rename/drop、table/view/function create/drop 能力字段。
- `jdbc-drivers.yml` 与 `JdbcDriverResolver` 新增 namespace 能力解析；为 PostgreSQL 补齐 view/function 删除 SQL。
- 新增命名空间接口：`POST /api/schema/namespace/create`、`/rename`、`/drop`，并新增 `SchemaNamespaceJdbcRepository` 统一封装动态 SQL。
- 新增对象删除接口：`POST /api/schema/object/drop`，仅支持视图/函数，复用 `SchemaObjectDefinitionJdbcRepository` 中的 drop SQL。
- `saveObjectDefinition` 放开“仅限已存在对象”限制，配合前端 create 模式支持新建视图/函数，同时保留对象名与 SQL 头一致校验。
- 已保存查询新增接口：`POST /api/editor/saved-query/update`、`/remove`；`SavedQueryMapper` 增加按 ID 查询、更新、删除。
- 前端 `useConnectionBrowserModule` 重构为动作注册表 + 动态菜单渲染：支持 `connection | database | category | object` 四类上下文，菜单名称随节点类型变化，不支持项自动隐藏。
- 前端新增 namespace 创建/重命名弹窗，分组节点右键支持“新建表/视图/函数/查询”。
- `useObjectDefinitionEditorModule` 新增 create 模式与默认 SQL 模板，支持新建视图/函数；`QueryWorkspaceTab` 增加 `savedQueryId` 与 `savedQueryEditMode`，支持从树节点编辑并覆盖保存查询。
- `StudioShell.vue` 右键菜单改为动作数组渲染，避免模板中针对对象类型硬编码分支。

## 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端构建：`npm run build` 通过。
- 后端构建：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 后端完整构建：`mvn -f apps/server/pom.xml clean package` 未通过，失败原因为仓库现有测试 `AiServiceImplAstValidationTest.buildRepairPrompt_keepsOnlyDynamicRepairContext` 与 `OnnxLocalRerankServiceImplTest.score_acceptsCrossEncoderModelInputs`，与本次改动无直接关联。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18087"` 成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4176 --strictPort` 成功，`http://127.0.0.1:4176/` 返回 `HTTP/1.1 200 OK`。

## 遗留项
- PostgreSQL/Oracle/SQL Server 的 namespace 操作依赖当前连接权限和数据库本身限制，建议后续结合真实实例做回归验证。
- 后端现有两条测试失败会阻断不带 `-DskipTests` 的完整 Maven 打包，本次未在该范围内修复。
