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


### 2026-03-12 16:17:12

## 2026-03-12 表结构编辑区高度占满修复

### 本次目标
- 修复表新建/编辑页面中“字段定义”“索引管理”区域未占满可用高度、底部留下大块空白的问题。

### 关键改动
- `TableEditor.vue` 为编辑器根节点补充 `flex: 1`，让组件本身作为中间面板的弹性子项撑满剩余高度。
- 为字段表格与索引表格新增自适应高度宿主容器，结合 `ResizeObserver` 动态计算表格滚动高度，替换原来的固定 `320/420` 写死高度。
- 补齐 `ant-tabs` 内容层、表格宿主容器和字段详情面板的 `flex/min-height` 约束，避免页签切换或详情面板展开后布局塌缩。
- `shell.css` 为 `.table-editor-structure-pane > .table-editor` 增加父级 flex 约束，确保父容器也将剩余高度分配给表编辑器。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18093"` 成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6067 --strictPort` 成功，`curl --noproxy '*' -I http://127.0.0.1:6067/` 返回 `HTTP/1.1 200 OK`。


### 2026-03-12 16:23:31

## 2026-03-12 BIGINT 长度对比修复

### 本次目标
- 修复数据库表编辑时 `BIGINT` 长度未被正确规范化，导致原本未变更的 `BIGINT(长度)` 字段被误判进入变更 SQL 的问题。

### 关键改动
- 前端 `apps/desktop/src/components/TableEditor.vue`
  - 新增列类型规范化逻辑，统一解析 `BIGINT(20)`、`DECIMAL(10,2)` 这类内联类型定义，拆分为基础类型、长度、精度参与比较。
  - `cloneColumns`、编辑态加载和草稿回写统一走规范化，避免 `columnSize` 在表单态中出现字符串/数字混用导致误判。
  - `colEq` 改为比较规范化后的类型、长度、精度，确保 `BIGINT` 长度参与差异判断。
  - `typeWithSize` 不再忽略 `BIGINT` 的长度输出，生成 SQL 时会保留 `BIGINT(长度)`。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18094"` 成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6068 --strictPort` 成功，`curl -I http://127.0.0.1:6068/` 返回 `HTTP/1.1 200 OK`。


### 2026-03-12 16:27:42

## 2026-03-12 表结构编辑器紧凑布局与行内详情

### 本次目标
- 缩小表结构编辑页表格字体和行高，减少输入框上下留白，在同一屏内容纳更多字段。
- 将字段详情面板从底部独立区域改为选中字段行下方的行内展开。

### 关键改动
- 前端 `apps/desktop/src/components/TableEditor.vue`
  - 字段表与索引表统一增加紧凑样式类，压缩表头/单元格 padding、输入框高度、下拉选择器高度和按钮尺寸。
  - 字段表新增 `expandedRowRender`，字段详情改为直接在当前字段行下方展开，不再占用底部整块区域。
  - 详情区重新排成紧凑网格，保留可空、主键、自增、默认值、时间扩展等编辑项，但减少上下间距。
  - 补充行点击选中与删除后回退选中逻辑，保证展开详情跟随当前字段。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18095"` 成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6069 --strictPort` 成功，`curl -I http://127.0.0.1:6069/` 返回 `HTTP/1.1 200 OK`。


### 2026-03-12 16:30:40

## 2026-03-12 字段类型大小写无关变更判断

### 本次目标
- 修复表结构编辑中判断 SQL 变更时对字段类型大小写敏感的问题，确保 `bigint`、`BIGINT`、`BigInt` 这类仅大小写不同的类型不会被误判为结构变更。

### 关键改动
- 前端 `apps/desktop/src/components/TableEditor.vue`
  - 新增 `sameColumnTypeDefinition`，在字段差异比较时统一使用规范化后的类型、长度、精度进行判断。
  - 字段类型比较明确收口为大小写无关，避免后续局部逻辑直接按原始 `dataType` 字符串比较。
- 前端 `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 列定义 SQL 生成统一将类型标准化为大写，避免不同页面/不同来源在展示层出现大小写不一致。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18096"` 成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6070 --strictPort` 成功，`curl -I http://127.0.0.1:6070/` 返回 `HTTP/1.1 200 OK`。


### 2026-03-12 16:41:29

## 2026-03-12 表结构更新失败前端误报成功修复

### 本次目标
- 修复表结构更新执行 SQL 失败时，后端已返回失败但前端仍提示“表结构更新成功”的问题。

### 关键改动
- 后端 `apps/server/src/main/java/com/sqlcopilot/studio/controller/SchemaController.java`
  - 表创建、修改、删除、清空接口改为在 `TableOperationVO.success=false` 时返回 `ApiResponse.fail(...)`，不再统一包成 `ApiResponse.success(...)`。
  - 新增表操作响应封装方法，避免 JDBC 执行失败被前端误判为 HTTP 成功业务成功。
- 前端 `apps/desktop/src/modules/studio/composables/useTableEditorModule.ts`
  - 表编辑执行接口返回值改为显式接收 `TableOperationVO`。
  - 即使接口层未来误返回 `code=0`，前端仍会继续校验 `result.success`，失败时统一走错误提示，不再直接弹成功消息。
- 前端 `apps/desktop/src/types/index.ts`
  - 补充 `TableOperationVO` 类型定义，统一表操作返回结构。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 Maven 打包：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18098"` 成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6072 --strictPort` 成功，`curl -I http://127.0.0.1:6072/` 返回 `HTTP/1.1 200 OK`。


### 2026-03-12 16:56:02

## 2026-03-12 BIGINT 精度误判与 uuid() 默认值修复

### 本次目标
- 修复表编辑器中只改动少数字段时，所有 `BIGINT(19)` 字段都被误判进入变更 SQL 的问题。
- 修复 `uuid()` 这类函数默认值在预览 SQL 中被错误包成字符串 `'uuid()'` 的问题。

### 关键改动
- 前端 `apps/desktop/src/components/TableEditor.vue`
  - `normalizeColumnTypeParts` 对非小数类型统一忽略 `decimalDigits`，避免 MySQL 元数据中的 `numeric_scale=0` 与前端草稿中的 `null` 产生伪差异。
  - `defaultSql` 增加函数表达式识别，`uuid()`、`now()` 这类默认值会按表达式输出，不再被自动加引号。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18099"` 成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6073 --strictPort` 成功，`curl -I http://127.0.0.1:6073/` 返回 `HTTP/1.1 200 OK`。
