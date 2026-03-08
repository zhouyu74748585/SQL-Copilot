# 主题：knowledge-context-sample-sql

## 本次目标
- 让知识中心的上下文切换方式与 SQL 查询页保持一致，使用相同风格的连接/数据库选择器。
- 让知识中心中的样例 SQL 正文编辑器支持与查询页一致的 SQL 补全体验。
- 在 SQL 查询页增加“保存为样例 SQL”入口，并将保存动作简化为只补充说明即可提交。

## 关键改动
- 修改文件：`apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 将 SQL 编辑器补全上下文从“仅依赖当前查询页 activeQueryTab”改为“按编辑器实例解析连接/数据库上下文”。
  - 新增编辑器上下文注册能力，使 Monaco 补全可以同时服务查询页和知识中心样例 SQL 编辑器。
  - 扩展 `handleSqlEditorMount()`，支持传入自定义上下文解析器，并可关闭查询页专属的 SQL 选区浮层行为。
- 修改文件：`apps/desktop/src/modules/studio/composables/useKnowledgeModule.ts`
  - 为知识中心增加独立的 `knowledgeConnectionId` / `knowledgeDatabaseName` 状态与切换处理逻辑。
  - 知识列表与保存逻辑改为基于知识中心当前选择的连接/数据库上下文，而不是被动跟随查询页。
  - 新增查询页“保存为样例 SQL”弹窗状态与提交逻辑，保存时自动带入当前查询页 SQL、连接和数据库，仅额外填写说明。
- 修改文件：`apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 在知识中心主区域接入与查询页一致样式的连接/数据库选择条。
  - 将样例 SQL 正文输入框从 `textarea` 替换为 Monaco 编辑器，并复用 SQL 补全能力。
  - 在查询页 SQL 工具栏新增“保存为样例 SQL”按钮与说明弹窗。
- 修改文件：`apps/desktop/src/modules/studio/styles/shell.css`
  - 为知识中心上下文条、样例 SQL Monaco 编辑器容器补充样式。
  - 复用查询页上下文条样式，并对知识中心场景做局部覆盖。

## 验证结果
- 前端类型检查：
  - `npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建（clean）：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
  - 首次在沙箱内执行因 `esbuild` 拉起子进程触发 `spawn EPERM`，改为提权后完成构建。
- 后端启动验证（clean）：
  - `mvn -f apps/server/pom.xml clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18087" "-Dspring-boot.start.jmxPort=9007"` 启动成功。
  - `GET http://127.0.0.1:18087/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - `mvn -f apps/server/pom.xml spring-boot:stop "-Dspring-boot.stop.jmxPort=9007"` 停止成功。
- 前端预览验证：
  - 使用 `npx vite preview --host 127.0.0.1 --port 55061` 启动预览。
  - 预览地址 `http://127.0.0.1:55061/` 成功拉起并完成 HTTP 探活。

## 说明
- 本轮未改动后端知识中心接口协议，保存样例 SQL 继续复用现有 `/api/knowledge/example/save`。
- 查询页“保存为样例 SQL”默认按当前查询页上下文自动判定作用域：
  - 有连接且有数据库时保存为 `DATABASE`
  - 仅有连接时保存为 `CONNECTION`
  - 无连接时退化为 `GLOBAL`

## 20260308174433 追加记录

### 本轮目标
- 将“样例SQL”和“术语管理”从左侧导航点击行为调整为打开独立工作台页签，而不是复用对象浏览页签。
- 将知识中心页面结构调整为与查询页一致的上下布局：顶部固定连接/数据库选择器，下方为列表区和详情区。
- 移除导航树与样例详情中的“当前上下文信息”展示，直接复用顶部连接选择组件作为上下文入口。

### 关键改动
- 修改文件：`apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 为知识中心增加独立的 `knowledgeTabs` 与 `activeKnowledgeTab` 工作台状态。
  - 让工作台宽度计算、页签存在性判断和激活兜底逻辑同时覆盖知识中心页签。
- 修改文件：`apps/desktop/src/modules/studio/composables/useKnowledgeModule.ts`
  - 将知识中心导航打开逻辑改为创建/激活独立知识页签。
  - 新增 `closeKnowledgeTab()`，支持关闭“样例SQL”“术语管理”页签。
  - 将知识中心上下文切换与数据重载逻辑绑定到顶部连接/数据库选择器，不再依赖旧的浏览器导航模式。
- 修改文件：`apps/desktop/src/modules/studio/composables/useConnectionBrowserModule.ts`
  - 对象浏览页签激活时显式回到 `connections` 模式，避免与知识中心独立页签状态混淆。
- 修改文件：`apps/desktop/src/modules/studio/composables/useUiShellModule.ts`
  - 右侧详情区拖拽宽度逻辑同时兼容对象浏览页签和知识中心页签。
- 修改文件：`apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 在工作台标签栏中增加知识中心页签展示与关闭按钮。
  - 左侧导航点击“样例SQL”“术语管理”时改为激活对应知识页签。
  - 知识中心主区域改为顶部连接/数据库选择器，下面是列表与详情双栏。
  - 移除导航树“当前上下文”文案，以及样例详情中的上下文摘要展示。
  - 对象浏览模板恢复为纯对象浏览内容，不再混入知识中心分支。

### 验证结果
- 前端类型检查：
  - `npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建（clean）：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端启动验证（clean）：
  - `mvn clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18087" "-Dspring-boot.start.jmxPort=9007"` 通过。
  - `GET http://127.0.0.1:18087/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - `mvn spring-boot:stop "-Dspring-boot.stop.jmxPort=9007"` 通过。
- 环境补充说明：
  - 默认端口 `18080` 已有现存 Java 进程占用，直接 clean 启动会因端口冲突失败；未主动终止该进程，以独立端口 `18087` 完成校验。
  - 现有前端预览 `http://127.0.0.1:55061/` 探活返回 `200`，可正常访问。

## 20260308175233 追加记录

### 本轮目标
- 将知识中心的连接/数据库选择器调整为独立占据工作台最顶部，与 SQL 查询页保持完全一致的布局位置和视觉样式。
- 抽出查询页、知识中心、表结构编辑页共用的连接上下文选择组件，避免同一套 UI 分散在多个模板中重复维护。

### 关键改动
- 新增文件：`apps/desktop/src/modules/studio/components/StudioConnectionContextBar.vue`
  - 抽出公共连接/数据库选择栏组件，统一承载顶部上下文切换 UI。
- 修改文件：`apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 查询页顶部连接栏改为复用公共组件。
  - 表结构编辑页顶部连接栏改为复用公共组件。
  - 知识中心顶部连接栏从内容区内部上移到工作台最顶层，和查询页处于相同层级。
  - 新增选择器事件适配函数，将公共组件事件转回查询页、知识中心、表结构编辑页各自的状态切换逻辑。
- 修改文件：`apps/desktop/src/modules/studio/styles/shell.css`
  - 去除知识中心选择栏的局部样式分叉，确保其与查询页使用同一套顶部条样式。

### 验证结果
- 前端类型检查：
  - `npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建（clean）：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端启动验证（clean）：
  - `mvn clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18087" "-Dspring-boot.start.jmxPort=9007"` 通过。
  - `GET http://127.0.0.1:18087/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - `mvn spring-boot:stop "-Dspring-boot.stop.jmxPort=9007"` 通过。
- 前端预览验证：
  - `GET http://127.0.0.1:55061/` 返回 `200`，页面资源可正常探活。

## 20260308175638 追加记录

### 本轮目标
- 修复知识中心引入顶部连接栏后，工作台内容被错误拆成四块的问题。
- 保持知识中心顶部连接栏仍与查询页共用同一套样式，但下方内容区恢复为“列表 + 详情”的两栏结构。

### 问题原因
- 知识中心沿用了对象浏览的单行 `workbench-browser` 网格布局。
- 顶部连接栏复用了查询页的 `query-shared-meta`，该元素默认占据工作台第一行。
- 两者组合后，知识中心内容区在没有第二行网格约束的情况下触发自动排版，导致页面被拆成四块。

### 修复内容
- 修改文件：`apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 将知识中心从 `workbench-browser` 类中拆出，单独使用 `workbench-knowledge` 布局模式。
- 修改文件：`apps/desktop/src/modules/studio/styles/shell.css`
  - 新增 `workbench-knowledge` 的两行网格定义。
  - 让左侧导航与左分隔条跨越两行。
  - 明确知识中心列表区、右分隔条、详情区都位于第二行。
  - 在响应式布局下补齐 `workbench-knowledge` 的降级规则，避免窄屏再次错位。

### 验证结果
- 前端类型检查：
  - `npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建（clean）：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端启动验证（clean）：
  - `mvn clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18087" "-Dspring-boot.start.jmxPort=9007"` 通过。
  - `GET http://127.0.0.1:18087/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - `mvn spring-boot:stop "-Dspring-boot.stop.jmxPort=9007"` 通过。
- 前端预览验证：
  - `GET http://127.0.0.1:55061/` 返回 `200`。

## 20260308180016 追加记录

### 本轮目标
- 将知识中心的“样例详情”“术语详情”在结构和视觉上与对象详情页保持统一。
- 统一详情页内部字号，避免知识中心详情区比对象详情区更松散或更像独立表单页。

### 关键改动
- 修改文件：`apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 将术语详情改成“详情摘要 + 编辑表单”的双层结构。
  - 将样例详情改成“详情摘要 + 编辑表单”的双层结构。
  - 摘要区直接复用对象详情页使用的 `detail-summary` / `detail-row` 信息样式。
- 修改文件：`apps/desktop/src/modules/studio/styles/shell.css`
  - 新增 `detail-form-panel`、`detail-form-actions`，统一知识详情正文编辑区的留白和按钮区节奏。
  - 统一知识详情表单标签、输入框、选择器、按钮字号为 `12px`，与对象详情页保持一致。

### 验证结果
- 前端类型检查：
  - `npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建（clean）：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端启动验证（clean）：
  - `mvn clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18087" "-Dspring-boot.start.jmxPort=9007"` 通过。
  - `GET http://127.0.0.1:18087/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - `mvn spring-boot:stop "-Dspring-boot.stop.jmxPort=9007"` 通过。
- 前端预览验证：
  - `GET http://127.0.0.1:55061/` 返回 `200`。
