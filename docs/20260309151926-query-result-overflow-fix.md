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


### 2026-03-13 16:11:57

## 本次目标
- 修复查询页结果集较大时切换页签明显卡顿的问题，复用数据浏览页的虚拟表格渲染思路。
- 修复 SQL 明确写了 `LIMIT 1000` 时，后端仍只返回 500 行的问题。
- 补充修复浏览页首次进入时“新建表/视图/函数”按钮禁用态样式异常，以及左右分割条拖拽热区过窄的问题。

## 关键改动
- 查询结果渲染改为复用 `TableDataVirtualGrid`，并在查询页签状态中缓存 `resultTableRows/resultTableColumns`，避免切换页签时重复构造大表数据。
- 前端执行 SQL 时显式传递 `maxRows: 5000`；后端新增 `SqlExecuteReq.maxRows`，服务端按 1-5000 归一化，并以 `maxRows + 1` 读取结果集判断是否截断。
- `SqlExecuteVO` 与共享 contracts 新增 `truncated` 字段；查询结果区底部补充“仅展示前 N 行”的提示。
- 浏览页依赖库上下文的“新建表/新建视图/新建函数”按钮在不可用时降为普通按钮，不再显示为失真的禁用主按钮样式。
- 恢复左右分割条的可拖拽热区为 6px，并改成“6px 热区 + 1px 视觉细线”的实现，保持细线观感同时恢复拖拽手感。

## 验证结果
- `npm run -w @sqlcopilot/desktop type-check` 通过。
- `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- `mvn -f apps/server/pom.xml clean package -Dfile.encoding=UTF-8` 通过。
- 后端 clean 启动验证：`mvn -f apps/server/pom.xml clean spring-boot:run -Dfile.encoding=UTF-8 -Dspring-boot.run.arguments=--server.port=18081` 启动成功，`http://127.0.0.1:18081/api/health` 返回 `ok`。
- 说明：默认端口 `18080` 当时已有本地实例占用，因此本轮 clean 启动改用 `18081` 验证，未主动中断现有实例。
- 前端预览验证：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6074` 返回 `HTTP 200`。
