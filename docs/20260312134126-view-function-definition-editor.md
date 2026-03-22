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


### 2026-03-12 15:16:16

## 2026-03-12 新建视图/函数页面布局修复

### 本次目标
- 修复新建视图、新建函数定义编辑页布局异常，被拆成四格的问题。

### 关键改动
- 调整 `apps/desktop/src/modules/studio/styles/shell.css`，为 `workbench-object-definition` 补齐与其他编辑页一致的桌面端两行网格规则。
- 明确定义对象定义编辑页左侧连接树、左侧分隔条的跨行布局，避免顶部上下文条出现后左侧区域只占首行。
- 为对象定义编辑区补充 `grid-column: 3 / 6`、`grid-row: 2` 定位，让编辑器稳定占据右侧主工作区，不再触发隐式列导致“四格”布局。
- 同步补齐响应式断点下 `workbench-object-definition` 的列配置与移动端回退规则，保证桌面和窄屏表现一致。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18088"` 成功，`http://127.0.0.1:18088/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6062 --strictPort` 成功，`http://127.0.0.1:6062/` 返回 `HTTP/1.1 200 OK`。


### 2026-03-22 18:59:24

## 2026-03-22 视图/函数新建按钮 icon hover 调整

### 本次目标
- 将对象浏览中“新建视图”“新建函数”入口统一为 icons 图标按钮。
- 去掉按钮上直接展示的操作文字，改为仅在 hover 时通过 tooltip 展示名称。

### 关键改动
- 调整 `apps/desktop/src/modules/studio/components/StudioShell.vue` 中视图、函数对象页工具栏：
  - 新建视图 / 新建函数按钮改为 `type="text"` 的纯 icon 按钮。
  - 相邻的新建查询按钮同步改为同一组 icon-only 交互样式，避免视觉上出现按钮名称混排。
  - hover 文案改为走 `tt()`，补齐中英双语能力，并增加 `aria-label` 与测试标识。
- 调整 `apps/desktop/src/modules/studio/styles/shell.css`：
  - 新增 `browser-toolbar-action-btn` 样式，为视图/函数工具栏按钮补充 hover/active 动效。
  - hover 时强化图标高亮与描边反馈，保持与现有 icon 工具按钮风格一致。

### 验证结果
- 前端依赖安装：`npm install` 成功。
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18089"` 成功，`http://127.0.0.1:18089/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6064 --strictPort` 成功，`http://127.0.0.1:6064/` 返回 `HTTP 200`。


### 2026-03-22 19:06:18

## 2026-03-22 定义编辑页按钮图标纠偏

### 本次目标
- 撤销上一轮误改到对象浏览页工具栏的按钮调整。
- 将新建视图/函数定义编辑页顶部的“刷新、保存、格式化 SQL、复制”改为图标按钮展示。
- 删除“复制 SQL”按钮，并移除替换后按钮上的 `type` 属性，避免颜色显示异常。

### 关键改动
- 调整 `apps/desktop/src/modules/studio/components/StudioShell.vue`：
  - 恢复对象浏览页 `views/functions` 工具栏为原始实现，不再修改该区域。
  - 将对象定义编辑页顶部的刷新、保存、格式化 SQL 按钮改为 icon-only 按钮，并通过 tooltip 展示名称。
  - 刷新改用 `refresh.svg`，保存改用 `save.svg`，格式化 SQL 改用 `pretty.svg`。
  - 删除对象定义编辑页顶部“复制 SQL”按钮。
  - 新按钮不再设置 `type` 属性，避免 Ant Design 按钮主题色干扰图标显示。
- 调整 `apps/desktop/src/modules/studio/styles/shell.css`：
  - 新增 `object-definition-action-btn` 样式，用于定义编辑页按钮的圆角与保存态视觉强化。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18090"` 成功，`http://127.0.0.1:18090/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6065 --strictPort` 成功，`http://127.0.0.1:6065/` 返回 `HTTP 200`。


### 2026-03-22 19:11:42

## 2026-03-22 定义编辑页 tooltip 遮挡修复

### 本次目标
- 修复新建视图/函数定义编辑页顶部图标按钮 hover 提示被上方 tab 行遮挡的问题。

### 关键改动
- 调整 `apps/desktop/src/modules/studio/components/StudioShell.vue`：
  - 将对象定义编辑页顶部“刷新、保存、美化 SQL”三个 tooltip 的弹出方向固定为 `placement="bottom"`。
  - 让 hover 提示在按钮下方展示，避开上方 tab 行的覆盖区域。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18091"` 成功，`http://127.0.0.1:18091/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6066 --strictPort` 成功，`http://127.0.0.1:6066/` 返回 `HTTP 200`。
