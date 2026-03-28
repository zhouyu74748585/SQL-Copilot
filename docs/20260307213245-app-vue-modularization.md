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

### 2026-03-08 09:48:20

## 追加记录（2026-03-08 09:48:20）- ER 图首次加载不渲染修复

### 问题现象
- 请求 ER 图后页面不立即显示，仅在切换到其他页签再切回后才显示。

### 根因分析
- 在 `confirmErTableSelection` 新建 ER Tab 的分支中，先创建了普通对象 `tab`，再放入 `erTabs`。
- 后续异步请求 `refreshErGraphForTab(tab, true)` 继续操作这个“原始对象引用”，导致 `tab.graph` 更新未命中 Vue 的响应式代理对象。
- 因此首次请求完成后视图无响应，直到发生页签切换等其它响应式变更才触发重渲染。

### 修复方案
- 修改文件：apps/desktop/src/modules/studio/composables/useStudioRuntime.ts
  - 在新建 ER Tab 后，立即从 `erTabs.value` 中按 `key` 取回响应式实例，再将该实例传给 `refreshErGraphForTab`。
  - 保证异步加载过程中对 `loading/graph/errorMessage` 的写入都作用在响应式对象上，首轮加载即可触发渲染。

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


### 2026-03-28 12:09:48

## ?????2026-03-28 12:10:00?- useStudioRuntime ??????

### ????
- ? `useStudioRuntime.ts` ?????????????????????????????
- ?? `useStudioController.ts` ????????????????????

### ????
- ?????`apps/desktop/src/modules/studio/composables/useStudioRuntime/`
- ?????
  - `types.ts`
    - ?? `DesktopBridge`?`ObjectRow`???/ER/???????????? runtime ??????
  - `constants.ts`
    - ??????????????????AI/???????
  - `state.ts`
    - ?? `useStudioRuntime` ???????????????????????
  - `browserRuntime.ts`
    - ? key ???????????????Redis????????????
  - `queryRuntime.ts`
    - ? key ?????????AI????????????????
  - `lifecycle.ts`
    - ?? `onMounted`?`onBeforeUnmount` ? `watch` ???/?????
- ?????`apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - ?????? `state + browserRuntime + queryRuntime`???? `setupStudioRuntimeLifecycle()`?
- ???
  - `useStudioRuntime.ts` ???? 17 ???????????????
  - ?? `StudioRuntime = ReturnType<typeof useStudioRuntime>` ?????????????????

### ????
- ???????
  - `npm run -w @sqlcopilot/desktop type-check` ???
- ?????clean??
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` ???
- ???????clean??
  - `mvn -f apps/server/pom.xml clean compile spring-boot:start "-Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18082"` ?????
  - `GET http://127.0.0.1:18082/api/health` ?? `{"code":0,"message":"success","data":"ok"}`?
  - `mvn -f apps/server/pom.xml spring-boot:stop` ?????
- ???????
  - ? `apps/desktop` ??? `npx vite preview --host 127.0.0.1 --port 55060`?
  - `GET http://127.0.0.1:55060` ?? `200`?
  - ???????????

### ??
- ?????????? `state.ts`????????????????????????????????????????????


### 2026-03-28 12:54:50

## ????
- ??? `useStudioRuntime` ???????????????????
- ????????????/???????????????? `state.ts`?

## ????
- ??? `apps/desktop/src/modules/studio/composables/useStudioRuntime/state.ts` ?????????????????? `createStudioRuntimeState()` ???????
- ?? `apps/desktop/src/modules/studio/composables/useStudioRuntime/connections.ts`?????????????
  - ????????????????`findSupportedDbType`?`isMultiDatabaseType`?`visibleDatabasesForConnection`?`isDatabaseContextVisibleForConnection`?`defaultPortForDbType` ??
  - ?? / AI / RAG ???????`defaultConnectionForm`?`defaultAiConfigForm`?`defaultRagConfigForm`?`normalizeModelOptions`??? `createConnectionSettingsHelpers()` ????????????????
- `state.ts` ???? `createConnectionRuntimeHelpers()` ? `createConnectionSettingsHelpers()` ????????????????????
- ?????????????? `utils.ts`?`cache.ts`?`redis-browser.ts`?`charts.ts`?`sql-editor.ts`?`connections.ts` ??????`state.ts` ? 10079 ?????? 9554 ??

## ????
- ?????????`npm run -w @sqlcopilot/desktop type-check`
- ???????`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- ?? clean ??????????`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`?`GET http://127.0.0.1:18082/api/health` ?? `{"code":0,"message":"success","data":"ok"}`
- ?? preview ????`npx vite preview --host 127.0.0.1 --port 55060` ?? HTTP 200
- ??? `mvn -f apps/server/pom.xml spring-boot:stop` ???????? preview ???

## ????
- ???????????? / AI ??? `state.ts` ??? `query-execution.ts` ? `ai-interaction.ts`???????????????


### 2026-03-28 13:19:20

## ????
- ??? `useStudioRuntime` ?????????????????????? `state.ts` ???? SQL ??????????????????

## ????
- ?? `apps/desktop/src/modules/studio/composables/useStudioRuntime/query-execution.ts`????????? `state.ts`?
  - SQL ??????`resolveSqlForAction`?`splitSqlStatements`
  - ?????????`normalizeRiskLevel`?`riskColor`
  - ????????`explainSqlForTab`?`ensureRiskConfirmedBeforeExecute`?`executeSqlForTab`
  - ???????`exportCsvForTab`?`exportResultTab`?`exportActiveQueryResult`
- `query-execution.ts` ?? `createQueryExecutionHelpers()` ???????????? `charts.ts`???????????SQL ?????????????? API ???
- `constants.ts` ?? `QUERY_RESULT_MAX_ROWS`???????????? `state.ts` ?????
- `state.ts` ??????? `createQueryExecutionHelpers()` ???????????????????????? 9554 ??? 9120 ??

## ????
- ?????????`npm run -w @sqlcopilot/desktop type-check`
- ???????`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- ?? clean ??????????`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`?`GET http://127.0.0.1:18082/api/health` ?? `{"code":0,"message":"success","data":"ok"}`
- ?? preview ????`npx vite preview --host 127.0.0.1 --port 55060` ?? HTTP 200
- ??? `mvn -f apps/server/pom.xml spring-boot:stop` ???????? preview ???

## ????
- ?????? `generateSqlForTab`?`generateChartPlanForTab`?`repairSqlForTab` ? AI ?? / ?????? `ai-interaction.ts`????????????????AI ??????????


### 2026-03-28 13:33:55

## ????
- ??????? `useStudioRuntime`?? AI ?? SQL / ?????????? `state.ts` ??????

## ????
- ?? `apps/desktop/src/modules/studio/composables/useStudioRuntime/ai-interaction.ts`?
- ??? AI ??????????
  - `generateSqlForTab`
  - `generateChartPlanForTab`
  - `generateChartFromMessage`
  - `generateManualChartForTab`
  - `editChartFromHistory`
- ??????????????????????????
  - `buildChartPrompt`
  - `chartTypeLabel`
  - `chartSummaryText`
  - `dedupeChartMessageContent`
  - `isChartConfigRenderable`
- `state.ts` ???? `createAiInteractionHelpers()` ?? AI ????????? API ?????`StudioShell.vue` ???????? `buildChartPrompt` ? `chartTypeLabel`?
- `state.ts` ?????????? 9120 ?????? 8642 ??

## ????
- ?????????`npm run -w @sqlcopilot/desktop type-check`
- ???????`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- ?? clean ??????????`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`?`GET http://127.0.0.1:18082/api/health` ?? `{"code":0,"message":"success","data":"ok"}`
- ?? preview ????`npx vite preview --host 127.0.0.1 --port 55060` ?? HTTP 200
- ??? `mvn -f apps/server/pom.xml spring-boot:stop` ???????? preview ???

## ????
- ???????? `sendAutoForTab` ? `repairSqlForTab` ?? `ai-interaction.ts`???????? AI ????????? `state.ts`?


### 2026-03-28 13:41:35

## ????
- ??????? AI ??? `state.ts` ????? `auto` ? `repair` ??????? `ai-interaction.ts`?

## ????
- `apps/desktop/src/modules/studio/composables/useStudioRuntime/ai-interaction.ts` ???????????
  - `autoActionTypeByIntent`
  - `sendAutoForTab`
  - `repairSqlForTab`
- `state.ts` ??????????????? `createAiInteractionHelpers()` ?? AI ???Auto ??????? SQL ??????
- ?????????`useStudioRuntime` ?????????? `autoActionTypeByIntent`?`sendAutoForTab`?`repairSqlForTab`?
- ????? `state.ts` ??????? AI ?????
- `state.ts` ?????????? 8642 ?????? 8310 ??

## ????
- ?????????`npm run -w @sqlcopilot/desktop type-check`
- ???????`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- ?? clean ??????????`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`?`GET http://127.0.0.1:18082/api/health` ?? `{"code":0,"message":"success","data":"ok"}`
- ?? preview ????`npx vite preview --host 127.0.0.1 --port 55060` ?? HTTP 200
- ??? `mvn -f apps/server/pom.xml spring-boot:stop` ???????? preview ???

## ????
- ?????????????????? `downloadActiveChart`?`downloadMessageChart`?`cacheChartImageWithRetry`?????? `charts.ts`/`chart-export.ts`?? `state.ts` ?????????


### 2026-03-28 13:51:22

## 概要
- 继续把 `useStudioRuntime/state.ts` 里的查询图表运行时能力往 `charts.ts` 真正拆出，解决“图表导出/缓存/下载逻辑仍堆在主文件里”的问题。

## 本次调整
- 在 `apps/desktop/src/modules/studio/composables/useStudioRuntime/charts.ts` 新增 `createChartRuntimeHelpers()`，集中承载以下运行时能力：
  - `exportChartPngDataUrl`
  - `saveChartImageCache`
  - `loadChartImageDataUrl`
  - `cacheChartImageWithRetry`
  - `downloadImage`
  - `downloadActiveChart`
  - `downloadMessageChart`
  - `hydrateHistoryChartImages`
- `charts.ts` 同时补充了 `CacheChartImageResult` 类型和图表缓存读写所需的 API / Desktop bridge 交互逻辑。
- `state.ts` 改为通过 `createChartRuntimeHelpers()` 组装查询图表导出链路，只保留 ER 图导出相关实现。
- 删除了 `state.ts` 中原本重复承载的查询图表运行时实现与本地 `CacheChartImageResult` 定义，进一步降低主文件体积。
- 本轮完成后，`state.ts` 行数从 8310 行继续降到 7546 行；`charts.ts` 增长到 425 行，开始承载明确的“图表模块”职责。

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后已执行 `mvn -f apps/server/pom.xml spring-boot:stop`，并停止 preview 进程

## 下一步
- 继续处理剩余的 ER 图导出能力，把 `exportErDiagramPngDataUrl` / `downloadActiveErDiagram` 从 `state.ts` 拆到更聚焦的 `charts.ts` 或后续 `er-diagram.ts`
- 再往前推进时，优先拆 `table-data` / `history` / `knowledge` 等仍停留在 `state.ts` 的工作台域实现


### 2026-03-28 15:19:59

## 概要
- 继续把 `useStudioRuntime/state.ts` 中的 ER 图域逻辑拆到独立模块，目标是让主文件不再同时承载 ER 关系规范化、开图、刷新和导出实现。

## 本次调整
- 新增 `apps/desktop/src/modules/studio/composables/useStudioRuntime/er-diagram.ts`，沉淀 ER 图模块的核心能力：
  - `touchErTab`
  - `normalizeErRelationConfidence`
  - `buildErRelationKey`
  - `mergePersistedErGraphState`
  - `createErDiagramRuntimeHelpers()`
- `createErDiagramRuntimeHelpers()` 统一接住了下面这组原本堆在 `state.ts` 的 ER 运行时逻辑：
  - `openErTableSelectModal`
  - `refreshErGraphForTab`
  - `confirmErTableSelection`
  - `exportErDiagramPngDataUrl`
  - `downloadActiveErDiagram`
- `state.ts` 改为通过 `createErDiagramRuntimeHelpers()` 组装 ER 图能力，并直接复用 `er-diagram.ts` 导出的关系规范化 / 排序函数。
- 删除了 `state.ts` 中原本重复承载的 ER 关系规范化、图合并、ER 选表开图、ER 刷新和 ER 导出实现。
- 本轮完成后，`state.ts` 行数从 7546 行继续降到 7303 行；新增的 `er-diagram.ts` 当前为 320 行，ER 图模块开始具备独立落点。

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后已执行 `mvn -f apps/server/pom.xml spring-boot:stop`，并按端口停止 preview 进程

## 下一步
- 继续拆 ER 图快照相关流程，把快照列表、打开快照、重命名、删除等逻辑也从 `state.ts` 移入 `er-diagram.ts`
- 或者转去处理 `history.ts` / `table-data.ts`，优先继续切掉工作台域中仍然完整滞留在 `state.ts` 的整块实现


### 2026-03-28 15:57:19

## 概要
- 继续把 `useStudioRuntime/state.ts` 里的查询会话 / 历史辅助逻辑拆到独立模块，目标是把会话标题、历史动作归一化、消息气泡样式等公共能力从主文件中抽离。

## 本次调整
- 新增 `apps/desktop/src/modules/studio/composables/useStudioRuntime/history.ts`，承载历史与会话标题相关的通用 helper。
- `history.ts` 当前接住了下面这组原本堆在 `state.ts` 的逻辑：
  - `sessionTitleOverrideKey`
  - `historyItemKey`
  - `findQueryTabBySession`
  - `queryTabConnectionNameById`
  - `normalizeTitleSource`
  - `buildSessionDefaultTitle`
  - `firstPromptForTitle`
  - `buildNewQueryPlaceholderTitle`
  - `applySessionTitle`
  - `loadSessionTitleOverrides`
  - `persistSessionTitleOverrides`
  - `cancelHistoryTitleEdit`
  - `assistantActionLabel`
  - `normalizeHistoryActionType`
  - `userBubbleClass`
- `state.ts` 改为通过 `createHistoryRuntimeHelpers()` 组装这批能力，对 `useHistoryModule.ts`、`StudioShell.vue` 的外部接口保持不变。
- 这轮没有改现有 `useHistoryModule.ts` 的行为，只是把它依赖的 runtime 公共能力从主文件中抽了出去。
- 本轮完成后，`state.ts` 行数从 7303 行继续降到 7149 行；新增的 `history.ts` 当前为 209 行。

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后已执行 `mvn -f apps/server/pom.xml spring-boot:stop`，并按端口停止 preview 进程

## 下一步
- 继续处理 `table-data` 相关 runtime helper，把表数据页签的公共状态辅助逻辑从 `state.ts` 往专用模块迁移
- 或者进一步把查询聊天流中的消息构造 / trace 辅助从 `state.ts` 抽到更聚焦的 query 子模块


### 2026-03-28 16:17:58

## 概要
- 继续把 `useStudioRuntime/state.ts` 从“连接与浏览器全家桶”里拆开，这轮重点处理连接配置/CRUD 与 schema 浏览基础能力，让连接域和浏览器域开始真正落到独立模块。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/connections.ts`，新增三组真实运行时能力：
  - `getDesktopBridge`、结果导出目录读写
  - `createConnectionUiHelpers()`，承载 AI/RAG 配置弹窗、数据库选项、切连接/切库等 UI 逻辑
  - `createConnectionCrudHelpers()`，承载连接分组弹窗、连接保存、数据库预览、连接测试、断开、删除、Schema 同步等 CRUD 逻辑
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/sql-editor.ts`，新增 `createSqlEditorRuntimeHelpers()`，把下面这组编辑器能力从 `state.ts` 抽走：
  - `resolveSelectedSqlSnippet`
  - `queryEditorLanguageByDbType`
  - `queryUnitLabelByDbType`
  - `generateActionLabelByDbType`
  - `explainActionLabelByDbType`
  - `analyzeActionLabelByDbType`
  - `canGenerateChartForTab`
  - `formatSqlText`
  - `formatSqlForTab`
  - `formatObjectDefinitionSql`
- 新增 `apps/desktop/src/modules/studio/composables/useStudioRuntime/schema-browser.ts`，先接住浏览器侧基础能力：
  - `prepareConnectionTreeData`
  - `loadDatabaseListForConnection`
  - `loadNamespaceList`
  - `refreshVectorizeStatusForConnection`
  - `refreshAllVectorizeStatuses`
  - `pruneVectorizeStatusMap`
  - `startVectorizeStatusPolling`
  - `stopVectorizeStatusPolling`
- `state.ts` 改为只做这些 helper 的组装与状态承载，不再直接持有上述实现；这轮完成后：
  - `state.ts` 从 7149 行继续降到 6519 行
  - `connections.ts` 增长到 985 行，开始真正承载连接域主逻辑
  - `sql-editor.ts` 增长到 340 行
  - 新增的 `schema-browser.ts` 当前为 212 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续把 `loadSupportedDbTypes`、`loadConnectionGroups`、`loadConnections` 这组连接装载逻辑并入连接模块，进一步缩小 `state.ts`
- 再往前推进时，优先拆 `table-data`、`knowledge`、查询消息流辅助，逐步把剩余 6000+ 行主文件压成真正的状态装配入口


### 2026-03-28 16:51:06

## 概要
- 继续沿着连接域和浏览器域拆分，这轮把“连接装载主链”和“连接运行时清理”也从 `state.ts` 挪到了专门模块里，进一步压缩主文件。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/connections.ts`，新增 `createConnectionLoadingHelpers()`，承载以下连接装载逻辑：
  - `loadSupportedDbTypes`
  - `loadConnectionGroups`
  - `loadConnections`
  - `refreshConnections`
- 上述 helper 保持了原有行为，包括：
  - 连接列表刷新后的 tab 过滤
  - 多库连接的数据库可见性修正
  - 无连接时的工作台 / 历史 / ER 快照状态清空
  - 自动恢复默认连接并尝试加载 overview
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/schema-browser.ts`，新增 `createConnectionBrowserStateHelpers()`，集中承载：
  - `invalidateConnectionMetadataCaches`
  - `setConnectionRuntimeStatus`
  - `resetConnectionRuntimeState`
- `state.ts` 改为只负责组装这些 helper，并保留 `collapseConnectionNode` 这类仍被本地浏览器流程直接使用的小函数。
- 本轮完成后：
  - `state.ts` 从 6519 行继续降到 6385 行
  - `connections.ts` 增长到 1188 行
  - `schema-browser.ts` 增长到 368 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续处理浏览器树与对象浏览主链，把 `loadOverview`、对象列表加载、树节点展开等大块逻辑往 `schema-browser.ts`/`browser-runtime` 方向迁移
- 再之后优先拆 `table-data`、`knowledge`、查询消息流辅助，让 `state.ts` 继续从“实现文件”往“装配文件”收缩


### 2026-03-28 16:58:46

## 概要
- 继续把浏览器对象刷新链路从 `state.ts` 拆到 `schema-browser.ts`，优先处理对象列表加载、当前页刷新、分类切换这一组成块逻辑。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/schema-browser.ts`，新增 `createSchemaObjectBrowserHelpers()`，集中承载：
  - `loadObjectNames`
  - `refreshCurrentObjects`
  - `refreshCurrentPageObjects`
  - `loadCategoryObjects`
- 这组 helper 保持原行为，包括：
  - 强制刷新时清除数据库列表/对象缓存后重新回源
  - 表分类刷新时走 `loadOverview`
  - 查询分类刷新时加载 saved queries
  - 非查询对象刷新后自动尝试重新加载当前选中对象详情
- `state.ts` 改为只组装这些对象浏览 helper，不再自己实现这组刷新链路。
- 本轮完成后：
  - `state.ts` 从 6385 行继续降到 6330 行
  - `schema-browser.ts` 增长到 505 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续处理浏览器树主链，把 `loadTreeChildrenByKey`、`handleTreeSelect`、`handleTreeExpand` 以及 `ensureConnectionTreeExpanded` 周边逻辑继续往 `schema-browser.ts` 迁移
- 或者转去拆 `table-data` / `knowledge` / 查询消息流辅助，优先切掉仍然在 `state.ts` 中连续成块的大段实现


### 2026-03-28 17:17:55

## 概要
- 继续把浏览器树主链从 `state.ts` 拆开，这轮把树节点 key 生成、树展开预加载、连接树展开保证以及树节点选择主链都迁到了 `schema-browser.ts`。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/schema-browser.ts`，新增并接住以下树浏览 helper：
  - `buildDatabaseNodeKey`
  - `buildDatabaseRootNodeKey`
  - `buildCategoryNodeKey`
  - `buildObjectNodeKey`
  - `createSchemaTreeHelpers()`
  - `createSchemaTreeSelectionHelpers()`
- 其中 `createSchemaTreeHelpers()` 当前承载：
  - `expandCategoryNode`
  - `expandConnectionNode`
  - `ensureConnectionTreeExpanded`
  - `loadTreeChildrenByKey`
  - `handleTreeExpand`
- `createSchemaTreeSelectionHelpers()` 当前承载：
  - `handleTreeSelect`
- `state.ts` 改为只组装这些树浏览 helper，不再直接实现上述树主链逻辑。
- 本轮完成后：
  - `state.ts` 从 6330 行继续降到 6145 行
  - `schema-browser.ts` 增长到 836 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续把浏览器右键菜单、对象选择后的详情加载联动往 `schema-browser.ts` 迁移，进一步清空 `state.ts` 中剩余的大段浏览器实现
- 或者转去拆 `table-data` / `knowledge` / 查询消息流辅助，优先继续压缩仍然停留在主文件中的工作台域与对话域逻辑


### 2026-03-28 18:48:23

## 概要
- 继续把浏览器对象详情主链从 `state.ts` 拆开，这轮除了修复右键菜单 helper 的装配顺序外，也把对象选择与详情加载链路正式迁到了 `schema-browser.ts`。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/schema-browser.ts`，新增 `createSchemaObjectDetailHelpers()`，集中承载：
  - `clearObjectDetail`
  - `loadObjectDetail`
  - `selectObject`
- 这组 helper 保持原行为，包括：
  - Redis key 选中时同步 `redisSelectedRowKey`
  - `queries` 类型对象继续走保存查询打开逻辑
  - 表/视图对象继续按数据库类型生成默认查询 SQL
  - Redis / 表 / 视图 / 函数对象详情继续分别走原有接口加载
- `state.ts` 改为只组装这组对象详情 helper，不再自己实现对象详情清空、对象详情加载、对象选择联动。
- 同时将 `createSchemaContextMenuHelpers()` 的装配位置前移，修复 `closeContextMenu` 在 `ER` helper 与树选择 helper 中的先后声明问题。
- 本轮完成后：
  - `state.ts` 从 6145 行继续降到 5946 行
  - `schema-browser.ts` 增长到 1135 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续把浏览器右键菜单剩余动作、对象列表页相关菜单动作与 schema/namespace 详情联动继续往 `schema-browser.ts` 迁移
- 然后优先转去拆 `table-data`、`knowledge`、查询消息流辅助，让 `state.ts` 从“浏览器残余实现文件”继续往“多域组装入口”收缩


### 2026-03-28 18:53:24

## 概要
- 继续把 Redis 浏览器编辑域从 `state.ts` 拆开，这轮把 Redis 键编辑/删除弹窗相关逻辑迁到了 `redis-browser.ts`。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/redis-browser.ts`，新增 `createRedisKeyModalHelpers()`，集中承载：
  - `openCreateRedisKeyModal`
  - `openEditRedisKeyModal`
  - `closeRedisKeyModal`
  - `confirmRedisKeyModal`
  - `deleteRedisKey`
- 同时将 `normalizeRedisValueType`、`parseRedisEditorEntries` 这组 Redis 编辑辅助函数一起迁到 `redis-browser.ts`，让 Redis 键编辑 payload 的组装也回到 Redis 域内部。
- `state.ts` 改为只组装 Redis 键编辑 helper，不再直接实现 Redis 键创建/编辑/删除弹窗流程。
- 清理了 `state.ts` 中随之失效的 Redis 编辑相关导入。
- 本轮完成后：
  - `state.ts` 从 5946 行继续降到 5838 行
  - `redis-browser.ts` 增长到 384 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续把 Redis 浏览器剩余的层级展开、分页装载、搜索联动也往 `redis-browser.ts` 收拢
- 或者转去拆 `table-data`、`knowledge`、查询消息流，优先处理仍然在 `state.ts` 中连续成块的工作台域逻辑


### 2026-03-28 19:21:38

## 概要
- 继续把 Redis 浏览器主链从 `state.ts` 拆开，这轮把缓存、根列表加载、子节点展开和 `load more` 这组浏览能力正式迁到了 `redis-browser.ts`。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/redis-browser.ts`，新增 `createRedisBrowserRuntimeHelpers()`，集中承载：
  - `invalidateRedisBrowserCache`
  - `fetchRedisBrowserPage`
  - `loadRedisBrowserRows`
  - `loadRedisBrowserChildren`
  - `handleRedisBrowserExpand`
  - `toggleRedisBrowserPath`
  - `loadMoreRedisBrowserRows`
- 同时将 `normalizeRedisBrowserPath`、`buildRedisBrowserQueryUrl` 这组 Redis 浏览器内部辅助函数一起迁到 `redis-browser.ts`。
- `state.ts` 改为只组装 Redis 浏览器 runtime helper，不再直接实现 Redis 浏览器主链。
- 顺手清理了 `state.ts` 中已失效的 Redis 浏览器旧导入。
- 本轮完成后：
  - `state.ts` 从 5838 行继续降到 5605 行
  - `redis-browser.ts` 增长到 656 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续优先拆 `table-data` / `knowledge` / 查询消息流，让 `state.ts` 里剩余的大块工作台和对话域逻辑继续往独立模块迁移
- 如果继续处理浏览器域，则剩下更适合再收的是 `loadOverview` 与表统计轮询这组 schema/kv 概览联动


### 2026-03-28 19:59:13

## 概要
- 继续把浏览器域里最重的一组概览与缓存逻辑从 `state.ts` 拆开，这轮把 `overview / table-stats / table-name-cache / query-table-detail-cache` 这条主链迁到了 `schema-browser.ts`。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/schema-browser.ts`，新增 `createSchemaOverviewRuntimeHelpers()`，集中承载：
  - `loadOverview`
  - `clearTableStatsPollingTimer`
  - `clearAllTableStatsPollingTimers`
  - `applyTableStatsSnapshot`
  - `isDatabaseNodeExpanded`
  - `collectExpandedDatabaseTargets`
  - `fetchTableStatsForDatabase`
  - `scheduleTableStatsForExpandedDatabases`
  - `loadTableNamesByConnection`
  - `ensureTableNamesLoaded`
  - `ensureQueryTableDetailLoaded`
- 这次不是简单挪函数名，同时把 schema / kv 概览加载、表统计轮询、表名缓存和查询场景下的表结构缓存都收拢到了同一个浏览器模块里。
- `state.ts` 改成通过 `schemaOverviewRuntime` 组装并转发这些能力，保留现有对外 API 和已有调用点不变。
- 本轮完成后：
  - `state.ts` 从 5605 行继续降到 5356 行
  - `schema-browser.ts` 增长到 1509 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续优先拆 `table-data` / `knowledge` / 查询消息流，让 `state.ts` 里的剩余工作台和对话域逻辑继续往独立模块迁移
- 如果继续清浏览器域，下一块更适合再收的是浏览器右键菜单剩余动作与对象操作命令链


### 2026-03-28 20:05:45

## 概要
- 继续清理浏览器域残留动作链，这轮把连接树拖拽和向量化操作相关逻辑从 `state.ts` 迁到了 `schema-browser.ts`。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/schema-browser.ts`，新增 `createSchemaBrowserActionHelpers()`，集中承载：
  - `handleConnectionTreeDrop`
  - `openVectorizeOverview`
  - `enqueueDatabaseRevectorize`
  - `vectorizeSingleTable`
  - `interruptDatabaseVectorize`
- `state.ts` 改为只组装这组浏览器动作 helper，不再直接实现连接树拖拽与向量化操作链。
- 本轮拆分后，浏览器域里的“加载链”和“动作链”都进一步向 `schema-browser.ts` 聚合，`state.ts` 继续往装配层收缩。
- 本轮完成后：
  - `state.ts` 从 5356 行继续降到 5260 行
  - `schema-browser.ts` 增长到 1653 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续优先拆 `knowledge` / 查询消息流 / 剩余浏览器右键菜单动作，处理 `state.ts` 里还成块存在的工作台与对话域逻辑
- `table-data` 本身已经主要集中在 `useTableDataModule.ts`，后续更适合评估是否并入统一 `table-data.ts`，而不是继续从 `state.ts` 生拆


### 2026-03-28 20:15:36

## 概要
- 继续拆查询域残留的大块实现，这轮把查询聊天消息与 streaming trace 主链从 `state.ts` 迁到了新的 `query-chat.ts`，让 `state.ts` 进一步回到装配层角色。

## 本次调整
- 新增 `apps/desktop/src/modules/studio/composables/useStudioRuntime/query-chat.ts`，集中承载：
  - `toggleMessageTraceExpanded`
  - `flushStreamingQueryTab`
  - `bindQueryChatMessageRef`
  - `scrollToQueryChatMessage`
  - `appendUserChatMessage`
  - `appendAssistantThinkingMessage`
  - `removeQueryChatMessage`
  - `materializeAssistantErrorMessage`
  - `prepareAssistantMessage`
  - `extractThinkingContentFromTrace`
  - `ensureAssistantStreamingState`
  - `upsertStreamingTraceStage`
  - `upsertStreamingTraceLlmDelta`
  - `applyStreamTraceSnapshot`
  - `appendAssistantSqlMessage`
  - `appendAssistantTextMessage`
- `state.ts` 改为通过 `createQueryChatHelpers()` 组装查询聊天消息流，不再直接承载消息实体创建、trace 展开、滚动定位和 streaming 状态收口逻辑。
- 这次拆分后，`ai-interaction.ts` 继续专注 AI 请求与自动执行链，`query-chat.ts` 只负责聊天消息模型与展示态更新，边界比之前更清楚。
- 本轮完成后：
  - `state.ts` 从 5759 行降到 5413 行
  - `query-chat.ts` 新增 411 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续优先拆 `knowledge`、查询消息流剩余辅助链和浏览器域残留动作，让 `state.ts` 继续向装配层收缩
- 评估是否把仍留在 `state.ts` 中的对话保存、prompt 组装与超时重试辅助继续拆到更聚焦的查询域模块


### 2026-03-28 20:26:01

## 概要
- 继续拆查询域剩余辅助链，这轮把会话历史持久化并入 `history.ts`，同时把 AI 超时/中断/重试辅助抽到了新的 `ai-request.ts`，让 `state.ts` 再次收缩。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/history.ts`，新增并集中承载：
  - `saveConversationHistory`
  - `buildStructuredContextForTab`
  - `saveConversationHistoryOnce`
  - `applyResponseTokenSnapshot`
- 新增 `apps/desktop/src/modules/studio/composables/useStudioRuntime/ai-request.ts`，集中承载：
  - `timeoutRetryErrorMessage`
  - `isTimeoutErrorMessage`
  - `getErrorMessage`
  - `isAbortError`
  - `clearUserRetryState`
  - `markUserMessageRetryable`
  - `mergePromptWithSqlSnippet`
  - `isAiRequestAbortedMessage`
  - `postAiApiWithTimeout`
  - `postAiStreamWithTimeout`
  - `looksLikeExecutableQueryText`
  - `looksLikeSqlText`
- `state.ts` 改为只组装这两组 helper，不再直接承载对话落库、token 快照回填、AI 请求超时控制和重试状态维护。
- 这次拆分后，查询域已经明显分成了 `query-chat`、`ai-interaction`、`history`、`ai-request` 四条更聚焦的职责线。
- 本轮完成后：
  - `state.ts` 从 5413 行继续降到 5136 行
  - `history.ts` 增长到 382 行
  - `ai-request.ts` 新增 190 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续优先清理 `state.ts` 中剩余的查询编辑器挂载、对象操作命令链和工作台通用展示辅助
- 评估是否把 `buildExecutionPreview / chatExecutionColumns` 等查询结果消息辅助进一步并回查询执行或图表域模块


### 2026-03-28 20:35:25

## 概要
- 继续拆查询编辑器运行时，这轮把 SQL 编辑器的补全 provider、自动建议、选区浮层和挂载初始化链从 `state.ts` 迁回了 `sql-editor.ts`。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/sql-editor.ts`，新增 `createSqlEditorInteractionHelpers()`，集中承载：
  - `tableNameSuggestions`
  - `sqlKeywordSuggestions`
  - `hasKeywordSuggestion`
  - `hasTableSuggestion`
  - `shouldAutoTriggerSuggest`
  - `registerSqlCompletionProvider`
  - `registerSqlAutoSuggest`
  - `readSelectedSql`
  - `hideSqlSelectionPopover`
  - `updateSqlSelectionPopoverPosition`
  - `syncSelectedSqlForActiveTab`
  - `registerSqlSelectionTracker`
  - `registerSqlSelectionPopoverTrigger`
  - `registerSqlScrollTracker`
  - `warmupTableSuggestions`
  - `handleSqlEditorMount`
- 同时把 `sqlKeywords` 常量一并归回 `sql-editor.ts`，避免 `state.ts` 继续承载 SQL 编辑器内部实现细节。
- `state.ts` 改为只做编辑器交互 helper 的状态注入和对外转发，不再自己实现 Monaco 补全与选区联动逻辑。
- 这次拆分后，SQL 编辑器域已经基本形成“格式化/解析 helper + 交互 runtime helper”两层结构。
- 本轮完成后：
  - `state.ts` 从 5136 行继续降到 4800 行
  - `sql-editor.ts` 增长到 940 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续优先清理 `state.ts` 中剩余的查询结果展示辅助、对象操作命令链和通用 UI 展示函数
- 评估是否把 `buildExecutionPreview / chatExecutionColumns` 等结果消息辅助并回查询执行或图表模块，进一步压缩查询域残留


### 2026-03-28 21:05:23

## 概要
- 继续清理查询结果展示域残留，这轮把 `state.ts` 里的结果预览重复实现去掉，并把结果消息列定义正式归回 `charts.ts`。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/charts.ts`，新增并导出 `chatExecutionColumns()`。
- 移除 `apps/desktop/src/modules/studio/composables/useStudioRuntime/state.ts` 中重复的：
  - `buildExecutionPreview`
  - `chatExecutionColumns`
- `state.ts` 改为直接复用 `charts.ts` 中已有的 `buildExecutionPreview()` 和新导出的 `chatExecutionColumns()`，避免查询结果预览逻辑在多个文件各维护一份。
- 本轮完成后：
  - `state.ts` 从 4800 行继续降到 4762 行
  - `charts.ts` 增长到 471 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续优先清理 `state.ts` 中剩余的对象操作命令链和通用 UI 展示函数
- 评估是否将连接树图标、对象图标和复制/DDL 生成辅助继续并入更聚焦的浏览器或工具模块


### 2026-03-28 21:15:43

## 概要
- 继续清理 `state.ts` 中的纯展示逻辑，这轮把环境标签、树/对象图标和向量化展示辅助统一并回了 `utils.ts`。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/utils.ts`，新增并导出：
  - `formatVectorizeProvider`
  - `normalizeEnv`
  - `envTagText`
  - `envTagClass`
  - `envTagIcon`
  - `nodeIconComponent`
  - `dbIconUrl`
  - `treeNodeIconUrl`
  - `treeTitleIconSrc`
  - `browserObjectIconSrc`
- 同时让 `utils.ts` 自己持有这批展示 helper 所需的图标组件和资源图片依赖，不再让 `state.ts` 直接维护这些 UI 细节。
- `state.ts` 删除了上述 helper 的本地实现，改为统一复用 `utils.ts` 导出。
- 本轮完成后：
  - `state.ts` 从 4762 行继续降到 4571 行
  - `utils.ts` 增长到 422 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续优先清理 `state.ts` 中剩余的对象操作命令链和 DDL/复制辅助
- 评估是否把对象查询 SQL、建表 SQL 生成与复制动作进一步并入更聚焦的工具或浏览器模块


### 2026-03-28 21:23:16

## 概要
- 继续清理对象操作工具链，这轮把对象查询 SQL、建表 SQL 拼装和通用复制逻辑统一并回了 `utils.ts`。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/utils.ts`，新增并导出：
  - `quoteSqlIdentifier`
  - `quoteSqlObjectName`
  - `buildObjectQuerySql`
  - `buildColumnSqlDefinition`
  - `buildCreateTableSql`
  - `copyTextContent`
- `state.ts` 删除了上述 DDL/复制工具的本地实现，改为直接复用 `utils.ts` 导出。
- `state.ts` 中保留 `copyCreateTableSql` 和 `copyTableEditorSql` 这两个与当前页面状态绑定的薄包装动作，其余纯工具逻辑已全部下沉到工具模块。
- 本轮完成后：
  - `state.ts` 从 4571 行继续降到 4388 行
  - `utils.ts` 增长到 538 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续优先清理 `state.ts` 中剩余的对象操作命令链
- 评估是否把保存查询、对象打开/跳转、浏览器动作包装继续向更聚焦模块收口


### 2026-03-28 21:33:08

## 概要
- 继续清理查询工作台域，这轮把“新建查询 Tab / 保存查询 / 打开保存查询 / 关闭 Tab / 请求中断 / 工作台激活兜底”整块从 `state.ts` 抽到了独立模块。

## 本次调整
- 新增 `apps/desktop/src/modules/studio/composables/useStudioRuntime/query-workbench.ts`，导出 `createQueryWorkbenchHelpers()`，统一承载：
  - `openAiQueryTab`
  - `createQueryTab`
  - `savedQueryCacheKey`
  - `savedQueriesByDatabase`
  - `loadSavedQueries`
  - `openSaveQueryModal`
  - `saveCurrentQuery`
  - `openSavedQueryTab`
  - `openSavedQueryTabByTitle`
  - `closeQueryTab`
  - `requestSqlExecutionInterrupt`
  - `hasWorkbenchTab`
  - `ensureActiveWorkbenchTab`
- `apps/desktop/src/modules/studio/composables/useStudioRuntime/state.ts` 改为通过 `createQueryWorkbenchHelpers()` 做装配，只保留状态与依赖注入，不再自己实现查询工作台主链。
- 为避免在浏览器对象详情 helper 初始化时触发提前引用，`createSchemaObjectDetailHelpers()` 里对 `openSavedQueryTabByTitle` 改成闭包转发。
- 同时清理了 `state.ts` 中已无必要的 `getApi`、`SavedQuerySaveReq` 直接依赖。
- 本轮完成后：
  - `state.ts` 从 4388 行继续降到 4158 行
  - `query-workbench.ts` 当前为 346 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续优先清理 `state.ts` 中剩余的对象操作命令链、对象打开/跳转包装和浏览器动作胶水代码
- 评估是否把查询工作台里和对象联动更强的部分继续并回浏览器域模块，进一步压缩 `state.ts`


### 2026-03-28 21:38:08

## 概要
- 继续清理查询结果展示域，这轮把结果视图切换、结果表缓存重建和手工图表配置更新链从 `state.ts` 统一下沉到了 `charts.ts`。

## 本次调整
- 扩展 `apps/desktop/src/modules/studio/composables/useStudioRuntime/charts.ts`，新增 `createQueryResultViewHelpers()`，承载：
  - `queryResultExportTooltip`
  - `setQueryResultViewMode`
  - `rebuildQueryResultTableCache`
  - `resizeActiveQueryResultColumn`
  - `setupManualChartConfigByResult`
  - `handleManualChartTypeChange`
  - `handleManualChartXAxisChange`
  - `handleManualChartYFieldsChange`
  - `handleManualChartSingleYFieldChange`
  - `handleManualChartSeriesFieldChange`
- `apps/desktop/src/modules/studio/composables/useStudioRuntime/state.ts` 改为通过 `createQueryResultViewHelpers()` 组装这批查询结果视图 helper。
- 过程中修正了一次装配顺序，把这批 helper 前移到 `query-execution` / `ai-interaction` 依赖之前，避免 block-scoped 提前引用报错。
- 本轮完成后：
  - `state.ts` 从 4158 行继续降到 4066 行
  - `charts.ts` 增长到 598 行

## 验证
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- 健康检查通过：`GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 可访问：`npx vite preview --host 127.0.0.1 --port 55060` 返回 HTTP 200
- 验证完成后将停止后端与 preview 进程

## 下一步
- 继续优先清理 `state.ts` 中剩余的对象操作命令链、浏览器对象跳转包装和连接树节点构造逻辑
- 评估是否把 `buildConnectionNode / buildCategoryChildren / getActiveDatabaseName` 继续收进浏览器域模块


### 2026-03-28 21:52:15

## ??
- ?????????????????????????????????????? `state.ts` ???? `schema-browser.ts`?

## ????
- ?? `apps/desktop/src/modules/studio/composables/useStudioRuntime/schema-browser.ts`????
  - `createSchemaBrowserStatusHelpers()`
  - `createSchemaTreeNodeHelpers()`
- `createSchemaBrowserStatusHelpers()` ?????
  - `clearBrowserObjectCollections`
  - `invalidateDatabaseMetadataCaches`
  - `clearDatabaseTableStatsCache`
  - `invalidateDatabaseListCache`
  - `handleDatabaseRenamedLocally`
  - `getDatabaseVectorizeStatus`
  - `getDatabaseVectorizeStatusRecord`
  - `canUseErFeature`
  - `resolveErUnavailableReason`
  - `isDatabaseVectorizing`
  - `databaseStatusLabel`
  - `databaseStatusClass`
  - `databaseStatusIcon`
  - `getActiveDatabaseName`
- `createSchemaTreeNodeHelpers()` ?????
  - `buildConnectionNode`
  - `buildCategoryChildren`
  - `getCategoryChildren`
- ????? `createConnectionBrowserStateHelpers()` ????????? `collapseConnectionNode`??????????? `state.ts` ?????
- `state.ts` ?????????
  - ?? `schemaBrowserStatusHelpers` ??????? helper
  - ?? `createSchemaTreeNodeHelpers()` ????????? helper
  - ???????? `getActiveDatabaseName()` ???????????????
- ??????
  - `state.ts` ? 4066 ????? 3758 ?
  - `schema-browser.ts` ??? 2192 ?

## ??
- ?????????`npm run -w @sqlcopilot/desktop type-check`
- ???????`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- ?? clean ?????`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- ???????`GET http://127.0.0.1:18082/api/health` ?? `{"code":0,"message":"success","data":"ok"}`
- ?? preview ????`npx vite preview --host 127.0.0.1 --port 55060` ?? HTTP 200
- ??????????? preview ??

## ???
- ?????? `state.ts` ???????? / ??????????????
- ????? `openQueryTabByObject`?`enrichPromptWithSchemaReferences`?`objectTypeLabel` ????????????????


### 2026-03-28 21:59:01

## ??
- ??????????????????????????????? prompt ??? `state.ts` ?????????????

## ????
- ?? `apps/desktop/src/modules/studio/composables/useStudioRuntime/query-workbench.ts`?
  - ?? `openQueryTabByObject()`
  - ????????????????SQL ???????????????????? helper
- ?? `apps/desktop/src/modules/studio/composables/useStudioRuntime/ai-interaction.ts`?
  - ????? `enrichPromptWithSchemaReferences()`
  - ????? `@table` / `@table.column` ????? prompt ??????? AI ????
- `apps/desktop/src/modules/studio/composables/useStudioRuntime/state.ts` ???????
  - `openQueryTabByObject`
  - `enrichPromptWithSchemaReferences`
- `state.ts` ???? `createQueryWorkbenchHelpers()` ? `ai-interaction.ts` ?????????????? API ???????
- ??????
  - `state.ts` ? 3758 ????? 3696 ?
  - `query-workbench.ts` ??? 381 ?
  - `ai-interaction.ts` ??? 1045 ?

## ??
- ?????????`npm run -w @sqlcopilot/desktop type-check`
- ???????`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- ?? clean ?????`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- ???????`GET http://127.0.0.1:18082/api/health` ?? `{"code":0,"message":"success","data":"ok"}`
- ?? preview ????`npx vite preview --host 127.0.0.1 --port 55060` ?? HTTP 200
- ??????????? preview ??

## ???
- ?????? `state.ts` ???????????????????????????
- ????? `toObjectType / objectTypeLabel / connectionStatusClass / connectionStatusText` ?????????????


### 2026-03-28 22:05:13

## ??
- ???? `state.ts` ????? helper?????????????????? `state.ts` ??????????????

## ????
- ?? `apps/desktop/src/modules/studio/composables/useStudioRuntime/utils.ts`?
  - ?? `toObjectType()`
  - ?? `objectTypeLabelForValue()`
  - ?? `createObjectPresentationHelpers()`
- ?? `apps/desktop/src/modules/studio/composables/useStudioRuntime/connections.ts`?
  - ?? `connectionStatusClassByState()`
  - ?? `connectionStatusTextByState()`
  - ?? `createConnectionStatusHelpers()`
- `apps/desktop/src/modules/studio/composables/useStudioRuntime/state.ts` ???????
  - `toObjectType`
  - `objectTypeLabel`
  - `connectionStatusClass`
  - `connectionStatusText`
- `state.ts` ???? `createObjectPresentationHelpers()` ? `createConnectionStatusHelpers()` ????????? API ?????
- ??????
  - `state.ts` ? 3696 ????? 3655 ?
  - `utils.ts` ??? 578 ?
  - `connections.ts` ??? 1309 ?

## ??
- ?????????`npm run -w @sqlcopilot/desktop type-check`
- ???????`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- ?? clean ?????`mvn -f apps/server/pom.xml clean compile spring-boot:start -Dstart-class=com.sqlcopilot.studio.SqlCopilotApplication -Dspring-boot.run.arguments=--server.port=18082`
- ???????`GET http://127.0.0.1:18082/api/health` ?? `{"code":0,"message":"success","data":"ok"}`
- ?? preview ????`npx vite preview --host 127.0.0.1 --port 55060` ?? HTTP 200
- ??????????? preview ??

## ???
- ?????? `state.ts` ??????????????????/???????
- ????? `formatSize / formatCompactCount / formatTime / formatDurationMs` ????????? `utils.ts`
