# 主题：query-editor-result-resize

## 记录

### 2026-03-12 22:09:52

## 本次目标
- 让查询界面右侧 SQL 编辑窗口和下方查询结果窗口支持手动调整高度占比。

## 关键改动
- 前端 `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 为查询右侧面板增加 `queryEditorPaneRef` 和上下分割条。
  - SQL 编辑区域改为动态高度，`MonacoEditor` 高度改为填满容器。
- 前端 `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 新增查询编辑区高度状态与拖拽状态。
  - 基于查询右侧面板真实高度重新计算结果表格纵向滚动区域，避免拖拽后表格高度失真。
- 前端 `apps/desktop/src/modules/studio/composables/useUiShellModule.ts`
  - 新增查询右侧上下分区拖拽逻辑。
  - 对拖拽范围增加上下限约束，保证 SQL 编辑区和结果区都保留可用最小高度。
  - 在窗口 resize 时重新收敛高度，避免面板缩小后布局被挤坏。
- 样式 `apps/desktop/src/modules/studio/styles/shell.css`
  - 为查询编辑区增加纵向布局样式和拖拽条样式。
  - 覆盖查询页 SQL 编辑器最小高度限制，使拖拽缩小时布局仍可收敛。
  - 为结果区设置最小高度，保证表头、表体和底部统计区仍可正常显示。

## 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端 clean 构建：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18103' '-Dfile.encoding=UTF-8'` 成功，`http://127.0.0.1:18103/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npx vite preview --host 127.0.0.1 --port 6058 --strictPort` 成功，`http://127.0.0.1:6058` 返回 `HTTP 200`。
