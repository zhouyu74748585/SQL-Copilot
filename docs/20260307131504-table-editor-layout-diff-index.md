# 主题：table-editor-layout-diff-index

## 本次目标
- 修复表结构编辑/新建页签布局，统一为左连接区 + 中间结构编辑 + 右侧SQL预览。
- 修复表编辑与新建页签切换时数据异常、不刷新的问题。
- 增加索引管理能力。
- 增加字段时间扩展属性（默认当前时间、更新时自动刷新）。

## 关键改动
- 前端 `apps/desktop/src/App.vue`
  - 新增表编辑工作台标签管理：`tableEditorTabs`、`activeTableEditorTab`、打开/关闭逻辑。
  - 浏览页工具栏新增“新建表”入口，右键对象菜单新增“编辑表结构”。
  - 表编辑页签改为与查询页一致的三段布局：
    - 顶部连接/数据库信息块。
    - 中间 `TableEditor` 结构编辑区。
    - 右侧 SQL 预览区（显示基于差异生成的DDL）。
  - 新增表编辑页签状态同步：`handleTableEditorChange` 持久化每个tab草稿与预览SQL，修复切换展示异常。
- 前端 `apps/desktop/src/components/TableEditor.vue`
  - 重构为“字段 + 索引”双面板。
  - 支持字段时间扩展属性：
    - `defaultCurrentTimestamp`
    - `onUpdateCurrentTimestamp`
  - 支持索引管理：索引名、唯一/普通、索引字段。
  - SQL预览改为实时生成：
    - 新建模式：`CREATE TABLE`（含主键/索引/表注释）
    - 编辑模式：基于基线结构生成`ALTER TABLE`差异语句（增删改列、主键变更、索引增删改、表注释变更）。
- 前端样式 `apps/desktop/src/style.css`
  - 新增 `workbench-table-editor` 布局规则与响应式适配。
  - 新增表编辑右侧SQL预览样式。
- 类型定义 `apps/desktop/src/types/index.ts`
  - `TableDetailVO` 扩展：`tableComment`、`indexes`、列级时间扩展属性。
- 后端 DTO
  - `TableDetailVO` 新增 `tableComment`、`indexes`、列时间扩展属性。
  - `TableCreateReq`/`TableAlterReq` 新增索引与时间扩展属性字段。
- 后端 `SchemaServiceImpl`
  - `getTableDetail` 增加表注释、索引明细读取。
  - MySQL 下增加列 `EXTRA` 读取并识别 `ON UPDATE CURRENT_TIMESTAMP`。
  - `buildCreateTableDDL` 支持索引与时间扩展属性拼接。

## 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 后端编译：`mvn -f apps/server/pom.xml -DskipTests compile` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081'`，健康检查 `/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 构建通过；`vite preview` 在当前环境端口监听被系统拒绝（`EACCES: permission denied`），未能完成HTTP预览探测。
