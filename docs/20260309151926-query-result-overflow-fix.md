# 主题：query-result-overflow-fix

## 记录

### 2026-03-09 15:19:26

## 本次目标
- 修复 SQL 查询页“查询结果”容器高度超出可视区，滚动后内容脱离屏幕的问题。

## 关键改动
- 调整前端滚动高度计算：
  - `tableScrollY` 从直接使用窗口高度改为 `Math.max(260, viewportHeight - 240)`。
  - `queryResultScrollY` 从直接使用窗口高度改为 `Math.max(180, viewportHeight - 560)`。
- 新增窗口尺寸同步函数 `syncViewportSize`，在 `onMounted` 注册 `resize` 监听、在 `onBeforeUnmount` 解除监听，确保缩放窗口后滚动高度实时更新。
- 样式层增加溢出约束：
  - `.query-editor-pane` 增加 `overflow: hidden;`
  - `.query-result-panel` 增加 `overflow: hidden;`
  避免结果区将整体页面继续向下撑出可视范围。

## 验证结果
- 前端构建通过：`npm run build`。
- 前端类型检查通过：`npm run type-check`。
- 后端 clean 启动验证通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.mainClass=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18090"`
  - 健康检查：`http://127.0.0.1:18090/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览验证通过：
  - `npx vite preview --host 127.0.0.1 --port 18091`
  - `http://127.0.0.1:18091` 返回 `HTTP/1.1 200 OK`。


### 2026-03-09 15:22:56

## 本次目标
- 在 SQL 查询结果区域底部显示当前结果行数，便于快速感知返回规模。

## 关键改动
- 在查询结果面板底部新增统计栏：`共 {{ activeResultRows.length }} 行`。
- 新增样式 `.query-result-footer`：固定底部展示，右对齐，保持与现有查询结果面板风格一致。

## 验证结果
- 前端构建通过：`npm run build`。
- 前端类型检查通过：`npm run type-check`。
- 后端 clean 启动验证通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.mainClass=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18090"`
  - 健康检查：`http://127.0.0.1:18090/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览验证通过：
  - `npx vite preview --host 127.0.0.1 --port 18091`
  - `http://127.0.0.1:18091` 返回 `HTTP/1.1 200 OK`。
