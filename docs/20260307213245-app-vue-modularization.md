# 主题：app-vue-modularization

## 本次目标
- 将 `apps/desktop/src/App.vue` 从超大单文件入口重构为最小入口。
- 将核心前端状态与行为迁移到 `modules/studio` 下的 composables。
- 增加模块化样式入口，将 `style.css` 改为聚合导入结构。

## 关键改动
- 入口拆分
  - `apps/desktop/src/App.vue` 改为最小入口，仅负责：
    - 调用 `useStudioController()`。
    - 挂载 `StudioShell`。
- Studio 模块目录新增
  - `apps/desktop/src/modules/studio/components/StudioShell.vue`
    - 承载原 `App.vue` 的完整 UI 结构（保留行为一致性）。
  - `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
    - 承载原 `App.vue` 的状态、计算属性、方法与生命周期逻辑。
  - `apps/desktop/src/modules/studio/composables/useStudioController.ts`
    - 作为聚合器，统一组装 runtime 与模块 composables。
  - 新增模块 composables（当前为稳定过渡层，保持行为等价）：
    - `useConnectionBrowserModule.ts`
    - `useQueryModule.ts`
    - `useErModule.ts`
    - `useTableEditorModule.ts`
    - `useUiShellModule.ts`
- 样式结构调整
  - 新增目录：`apps/desktop/src/modules/studio/styles/`
  - 新增样式文件：
    - `tokens.css`
    - `shell.css`（承载原 `style.css` 主体样式，确保视觉与行为不回归）
    - `browser.css`
    - `query.css`
    - `er.css`
    - `table-editor.css`
    - `modals.css`
  - `apps/desktop/src/style.css` 改为聚合入口（统一 `@import` 上述模块样式）。

## 验证结果
- 前端类型检查
  - `npm run -w @sqlcopilot/desktop type-check`：通过。
- 前端构建（clean）
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`：通过。
- 后端启动验证（clean）
  - 启动命令：
    - `mvn -f apps/server/pom.xml clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18082"`
  - 健康检查：
    - `GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 停止命令：
    - `mvn -f apps/server/pom.xml spring-boot:stop`
- 前端预览验证
  - 端口 `6046` 在当前环境出现 `EACCES`（权限拒绝绑定）。
  - 使用可用端口 `55060` 启动预览并验证：
    - `npx vite preview --host 127.0.0.1 --port 55060`
    - `GET http://127.0.0.1:55060` 返回 `200`。

## 说明
- 本轮以“行为等价、先稳再拆”为原则完成入口与模块基础重构。
- 现有业务行为、接口协议、数据结构保持不变。

## 追加记录（2026-03-07 22:54:13）- 继续拆分 runtime，模块从代理改为真实实现

### 本次目标
- 将 useConnectionBrowserModule/useQueryModule/useErModule/useTableEditorModule/useUiShellModule 从“仅返回 runtime”升级为“承载真实业务逻辑”。
- useStudioController 改为优先暴露模块实现，避免模块层继续空转。

### 关键改动
- 修改文件：pps/desktop/src/modules/studio/composables/useStudioController.ts
  - 先实例化五个模块并展开到顶层返回对象（...module），模块函数优先覆盖 runtime 同名函数。
  - 保留 connectionBrowserModule/queryModule/erModule/tableEditorModule/uiShellModule 子对象，便于后续按模块消费。
- 修改文件：pps/desktop/src/modules/studio/composables/useConnectionBrowserModule.ts
  - 实现连接/浏览域核心逻辑：ctivateBrowserTab、openCreateModal、openEditModal、closeContextMenu、	riggerContextAction、onObjectRow。
- 修改文件：pps/desktop/src/modules/studio/composables/useQueryModule.ts
  - 实现查询域核心逻辑：handleChatComposerKeydown、ppendSqlToEditor、ppendSelectedSqlToPrompt、	erminateAiExecutionForTab、	erminateSqlExecutionForTab、
etryUserMessage。
- 修改文件：pps/desktop/src/modules/studio/composables/useErModule.ts
  - 实现 ER 关系与标签域逻辑：关系归一化、键生成、方向文案、置信度处理、关系路由更新、删除 AI 关系、关闭 ER Tab。
- 修改文件：pps/desktop/src/modules/studio/composables/useTableEditorModule.ts
  - 实现表编辑域逻辑：打开/关闭编辑页签、加载表结构、变更同步、执行 DDL、清空/删表确认、刷新 schema 缓存。
- 修改文件：pps/desktop/src/modules/studio/composables/useUiShellModule.ts
  - 实现壳层交互逻辑：主题切换与持久化、窗口尺寸同步、左/中/右面板拖拽与事件解绑。

### 验证结果
- 前端类型检查：
pm run -w @sqlcopilot/desktop type-check 通过。
- 前端构建（clean）：
pm run -w @sqlcopilot/desktop build -- --emptyOutDir 通过。
- 后端启动验证（clean）：
  - mvn -f apps/server/pom.xml clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18082" 启动成功。
  - GET http://127.0.0.1:18082/api/health 返回 {"code":0,"message":"success","data":"ok"}。
  - mvn -f apps/server/pom.xml spring-boot:stop 停止成功。
- 前端预览验证：
  - 
px vite preview --host 127.0.0.1 --port 55060 启动后，GET http://127.0.0.1:55060 返回 200。
  - 验证后已结束预览进程。

## 追加记录（2026-03-08 07:18:02）- runtime 第二轮瘦身（UI壳层与查询交互迁出）

### 本次目标
- 继续缩减 useStudioRuntime.ts，将已在模块实现的壳层逻辑与查询交互逻辑从 runtime 中移除。

### 关键改动
- 修改文件：pps/desktop/src/modules/studio/composables/useStudioRuntime.ts
  - 移除并不再导出以下壳层函数：
    - 	oggleTheme
    - loadUiThemePreference
    - persistUiThemePreference
    - handleWindowResize
    - 左/中/右面板拖拽相关 start/handle/stop 系列函数
  - 移除并不再导出以下查询交互函数：
    - handleChatComposerKeydown
    - ppendSqlToEditor
    - ppendSelectedSqlToPrompt
    - 	erminateAiExecutionForTab
    - 	erminateSqlExecutionForTab
    - 
etryUserMessage
  - 调整生命周期：
    - runtime 的 onMounted 不再注册窗口 resize 和 UI 主题读取。
    - runtime 的 onBeforeUnmount 不再处理面板拖拽事件解绑。
    - runtime 中移除 uiTheme 持久化 watch。
- 修改文件：pps/desktop/src/modules/studio/composables/useUiShellModule.ts
  - 升级为完整壳层模块：
    - 新增 onMounted：注册 
esize 监听并加载主题偏好。
    - 新增 onBeforeUnmount：统一解绑 
esize 与四类拖拽事件。
    - 新增 watch(runtime.uiTheme)：主题切换自动持久化。
  - 类型从 StudioRuntime[...] 索引改为显式函数签名，降低对 runtime 导出面依赖。
- 修改文件：pps/desktop/src/modules/studio/composables/useQueryModule.ts
  - 类型同样改为显式 QueryTab/QueryMessage，避免继续依赖 runtime 已移除的函数签名。

### 验证结果
- 前端类型检查：
pm run -w @sqlcopilot/desktop type-check 通过。
- 前端构建（clean）：
pm run -w @sqlcopilot/desktop build -- --emptyOutDir 通过。
- 后端启动验证（clean）：
  - mvn -f apps/server/pom.xml clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18082"
  - 健康检查：GET http://127.0.0.1:18082/api/health 返回 {"code":0,"message":"success","data":"ok"}。
  - mvn -f apps/server/pom.xml spring-boot:stop 停止成功。
- 前端预览验证：
  - 
px vite preview --host 127.0.0.1 --port 55060 后，GET http://127.0.0.1:55060 返回 200。
  - 验证后已结束预览进程。

## 追加记录（2026-03-08 07:42:15）- runtime 第三轮瘦身（Connection/Browser 重复实现剥离）

### 本次目标
- 继续清理 useStudioRuntime.ts 中与 useConnectionBrowserModule 已重复的函数，实现真正下沉，减少 runtime 体积与重复维护点。

### 关键改动
- 修改文件：pps/desktop/src/modules/studio/composables/useStudioRuntime.ts
  - 删除并不再导出以下重复函数：
    - ctivateBrowserTab
    - openCreateModal
    - openEditModal
    - 	riggerContextAction
    - onObjectRow
  - 在 handleTreeSelect 中将 ctivateBrowserTab() 替换为直接设置 ctiveWorkbenchTab.value = browserTabKey，移除对 runtime 已删除函数的依赖。
  - 清理对应 return 导出项。
- 修改文件：pps/desktop/src/modules/studio/composables/useConnectionBrowserModule.ts
  - 模块类型定义改为显式签名，不再依赖 StudioRuntime['onObjectRow'] 这类已删除 runtime 成员类型。
  - 保持模块内行为不变（连接/对象浏览/右键菜单动作仍由模块实现）。

### 结果
- useStudioRuntime.ts 行数进一步下降至约 6800 行（上轮约 6964 行）。
- Connection/Browser 域入口行为继续由模块实现，runtime 不再保留对应重复函数体。

### 验证结果
- 前端类型检查：
pm run -w @sqlcopilot/desktop type-check 通过。
- 前端构建（clean）：
pm run -w @sqlcopilot/desktop build -- --emptyOutDir 通过。
- 后端启动验证（clean）：
  - 使用后台 Job 启动：mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18085"
  - 健康检查：GET http://127.0.0.1:18085/api/health 返回 {"code":0,"message":"success","data":"ok"}。
  - 验证后终止端口 18085 进程并确认服务已下线。
- 前端预览验证：
  - 
px vite preview --host 127.0.0.1 --port 55060 后，GET http://127.0.0.1:55060 返回 200。
  - 验证后已结束预览进程。


### 2026-03-08 08:55:50

## 追加记录（2026-03-08 08:53:00）- runtime 第四轮瘦身（ER/TableEditor 导出面剥离）

### 本次目标
- 继续按功能模块拆分 `useStudioRuntime.ts`，将已在 `useErModule`/`useTableEditorModule` 实现的重复能力从 runtime 导出面和函数体中剥离。

### 关键改动
- 修改文件：apps/desktop/src/modules/studio/composables/useStudioRuntime.ts
  - 删除 ER 重复导出项：
    - `touchErTab`
    - `normalizeErRelationDirection`
    - `normalizeErRelationType`
    - `erRelationKey`
    - `erRelationArrow`
    - `erRelationDirectionLabel`
    - `formatErRelationConfidence`
    - `normalizeErRelationConfidence`
    - `erRelationReasonPreview`
    - `handleErRelationRouteChange`
    - `removeErAiRelation`
    - `closeErTab`
  - 删除 TableEditor 重复导出项与对应实现：
    - `closeTableEditorTab`
    - `openNewTableEditor`
    - `openEditTableEditor`
    - `handleTableEditorChange`
    - `handleTableEditorSave`
    - `handleTableEditorRefresh`
    - `confirmTruncateTable`
    - `confirmDropTable`
    - `refreshSchemaMetadata`
    - `handleTableEditorExecute`
  - 保留 runtime 内部仍需使用的最小能力：
    - `touchErTab`（用于 runtime 内部 ER 快照相关更新）
    - `normalizeErRelationConfidence`（用于 runtime 内部计算）
    - `tableEditorSaving`（作为模块执行状态共享）
  - 删除仅用于已移除函数的类型定义：
    - `TableEditorChangePayload`
    - `ErRelationRouteChangePayload`
- 修改文件：apps/desktop/src/modules/studio/composables/useErModule.ts
  - 模块接口改为显式类型签名，不再依赖 `StudioRuntime['xxx']` 的被剥离成员。
  - 新增局部类型：`ErTab`、`ErRelation`、`ErRelationRouteChangePayload`，保持行为不变但降低对 runtime 导出面的耦合。

### 结果
- `useStudioRuntime.ts` 行数由约 6800 行下降到约 6410 行。
- ER 与 TableEditor 相关交互能力由模块层承担，runtime 继续收敛为基础状态与跨域能力载体。

### 验证结果
- 前端类型检查：
  - `npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建（clean）：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端启动验证（clean）：
  - `mvn -f apps/server/pom.xml clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18086"` 启动成功。
  - 健康检查：`GET http://127.0.0.1:18086/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - `mvn -f apps/server/pom.xml spring-boot:stop` 停止成功。
- 前端预览验证：
  - `npx vite preview --host 127.0.0.1 --port 55060` 启动后，`GET http://127.0.0.1:55060` 返回 `200`。
  - 验证后已终止预览进程并确认端口释放。

### 说明
- 验证过程中发现历史遗留后端进程占用 JMX 9001，已清理后完成本轮 clean 启动验证。

### 2026-03-08 09:12:30

## 追加记录（2026-03-08 09:12:30）- runtime 第五轮瘦身（ER 快照域下沉）

### 本次目标
- 继续按功能模块拆分 `useStudioRuntime.ts`，将 ER 快照相关逻辑整体下沉到独立模块，降低 runtime 体积与跨域复杂度。

### 关键改动
- 新增文件：apps/desktop/src/modules/studio/composables/useErSnapshotModule.ts
  - 新增 ER 快照模块，承载以下能力：
    - 快照列表键与激活态判定
    - 快照标题编辑（开始/取消/提交）
    - 快照删除
    - 快照分页加载、关键字搜索、滚动加载
    - 快照菜单点击刷新
    - 快照恢复到 ER Tab
    - 快照保存弹窗与保存提交
- 修改文件：apps/desktop/src/modules/studio/composables/useStudioController.ts
  - 接入 `useErSnapshotModule(runtime)`。
  - 将 `...erSnapshotModule` 展开到 controller 返回对象，并暴露 `erSnapshotModule` 子对象。
- 修改文件：apps/desktop/src/modules/studio/composables/useStudioRuntime.ts
  - 删除 ER 快照域函数实现与导出项（已迁移到 `useErSnapshotModule`）：
    - `erSnapshotItemKey`
    - `isErSnapshotItemActive`
    - `findErSnapshotSummaryById`
    - `updateErSnapshotTabsTitle`
    - `startErSnapshotTitleEdit`
    - `cancelErSnapshotTitleEdit`
    - `commitErSnapshotTitleEdit`
    - `removeErSnapshot`
    - `buildErSnapshotTabTitle`
    - `findErTabBySnapshotId`
    - `loadErSnapshotPage`
    - `applyErSnapshotKeywordSearch`
    - `handleErSnapshotMenuScroll`
    - `handleErSnapshotMenuClick`
    - `openErSnapshot`
    - `openErSnapshotSaveModal`
    - `confirmSaveErSnapshot`
  - 新增内部小函数 `resetErSnapshotTitleEditState()`，用于连接列表变化/删除连接时重置快照标题编辑状态（替代已下沉的取消函数）。
  - 清理 runtime 中已不再使用的 ER 快照请求/响应类型 import。

### 结果
- `useStudioRuntime.ts` 行数由约 6410 行下降到约 6015 行。
- ER 快照域形成独立 composable，controller 继续统一聚合，页面行为保持一致。

### 验证结果
- 前端类型检查：
  - `npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建（clean）：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端启动验证（clean）：
  - `mvn -f apps/server/pom.xml clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18086"` 启动成功。
  - 健康检查：`GET http://127.0.0.1:18086/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - `mvn -f apps/server/pom.xml spring-boot:stop` 停止成功。
- 前端预览验证：
  - `npx vite preview --host 127.0.0.1 --port 55060` 启动后，`GET http://127.0.0.1:55060` 返回 `200`。
  - 验证后已终止预览进程并确认端口释放。

### 2026-03-08 09:37:40

## 追加记录（2026-03-08 09:37:40）- runtime 第六轮瘦身（History 域下沉）

### 本次目标
- 继续按功能模块拆分 `useStudioRuntime.ts`，将会话历史菜单与历史会话恢复逻辑下沉到独立模块，进一步缩小 runtime 规模。

### 关键改动
- 新增文件：apps/desktop/src/modules/studio/composables/useHistoryModule.ts
  - 新增 History 模块，承载以下能力：
    - 历史菜单打开与连接切换处理
    - 历史会话标题显示/激活态判定
    - 历史会话改名与删除
    - 历史会话分页加载、关键字搜索、滚动加载
    - 历史详情回放并恢复到 Query Tab
    - 历史消息归一化（SQL/文本）、历史消息构建、历史 Tab 构建
- 修改文件：apps/desktop/src/modules/studio/composables/useStudioController.ts
  - 接入 `useHistoryModule(runtime)`。
  - 将 `...historyModule` 展开到 controller 返回对象，并暴露 `historyModule` 子对象。
- 修改文件：apps/desktop/src/modules/studio/composables/useStudioRuntime.ts
  - 删除 History 域函数实现与导出项（已迁移到 `useHistoryModule`）：
    - `handleHistoryMenuClick`
    - `historyItemDisplayTitle`
    - `isHistoryItemActive`
    - `startHistoryTitleEdit`
    - `removeHistorySession`
    - `commitHistoryTitleEdit`
    - `normalizeHistoryAssistantPayload`
    - `buildHistoryChatMessages`
    - `buildHistoryTabFromRows`
    - `loadHistorySessionPage`
    - `applyHistoryKeywordSearch`
    - `handleHistoryMenuScroll`
    - `openHistorySession`
  - 保留 runtime 中被其它域复用的标题与会话键工具函数（如 `applySessionTitle`、`sessionTitleOverrideKey` 等），保持低风险渐进拆分。

### 结果
- `useStudioRuntime.ts` 行数由约 6015 行下降到约 5646 行。
- History 域形成独立 composable，controller 统一聚合后，页面行为保持一致。

### 验证结果
- 前端类型检查：
  - `npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建（clean）：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端启动验证（clean）：
  - `mvn -f apps/server/pom.xml clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18086"` 启动成功。
  - 健康检查：`GET http://127.0.0.1:18086/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - `mvn -f apps/server/pom.xml spring-boot:stop` 停止成功。
- 前端预览验证：
  - `npx vite preview --host 127.0.0.1 --port 55060` 启动后，`GET http://127.0.0.1:55060` 返回 `200`。
  - 验证后已终止预览进程并确认端口释放。
