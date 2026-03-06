# 主题：browser-pane-spacing

## 记录

### 2026-03-06 11:52:37

## 本次目标
- 减少“我的连接 / 对象浏览”区域与上方页签 tab 的垂直间距。
- 缩小下方各模块之间的间距，提升页面紧凑度。

## 关键改动
- 修改文件：`apps/desktop/src/style.css`
- 调整内容区主容器间距：
  - `.studio-root .workbench` 的 `padding-top` 由 `8px` 下调为 `4px`。
  - `.studio-root .workbench` 的 `gap` 由 `10px` 下调为 `6px`。
- 调整窄屏规则同步收紧：
  - `@media (max-width: 1200px)` 下 `padding-top` 由 `8px` 下调为 `4px`，`gap` 由 `8px` 下调为 `6px`。
- 调整浏览页左侧模块与中间模块间隔：
  - `.studio-root .workbench.workbench-browser > .pane-left` 的 `margin-right` 由 `8px` 下调为 `4px`。

## 验证结果
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081` 启动成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后执行 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6046`，`HTTP/1.1 200 OK`。


### 2026-03-06 12:24:26

## 本次目标
- 所有页面主模块间距进一步缩小并统一（对象浏览、对象详情、SQL 编辑、对话模块）。
- 取消 SQL 编辑模块下部圆角。

## 关键改动
- 修改文件：`apps/desktop/src/style.css`
  - 统一模块间距：`.studio-root .workbench` 的 `gap` 调整为 `4px`（桌面与 `max-width:1200px` 响应式规则同步）。
  - 浏览页与 ER 页统一列间策略：
    - `.studio-root .workbench.workbench-browser, .workbench.workbench-er` 统一 `column-gap: 0`。
    - 左侧模块间距统一为 `4px`：`> .pane-left { margin-right: 4px; }`。
  - 分隔条宽度收紧：`.studio-root .pane-splitter` 从 `8px` 调整为 `4px`，使对象详情与中间模块、ER 右侧信息区间距与其它模块一致。
  - SQL 编辑模块底部圆角取消：`.studio-root .query-editor-pane { border-bottom-left-radius: 0; border-bottom-right-radius: 0; }`。
- 修改文件：`apps/desktop/src/App.vue`
  - 浏览页、ER 页动态网格列中分隔列宽由 `8px` 统一改为 `4px`：
    - `gridTemplateColumns: 270px minmax(...) 4px ...`

## 验证结果
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081` 启动成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6046` 启动成功，`HTTP/1.1 200 OK`。


### 2026-03-06 12:38:01

## 本次目标
- 下方各模块之间都支持横向拖拽调整宽度。
- 覆盖对象浏览页、ER页、SQL编辑/对话页。

## 关键改动
- 修改文件：`apps/desktop/src/App.vue`
  - 新增左侧分割条（连接区与中间区之间）：`pane-splitter-left`，统一可拖拽。
  - 浏览页、ER页原有右侧分割条统一为 `pane-splitter-right`。
  - 查询页新增中间分割条 `query-pane-splitter`，支持 SQL 编辑区与对话区左右拖动。
  - 新增宽度状态与拖拽逻辑：
    - 左侧宽度：`leftPaneWidth` + `startResizeLeftPane`。
    - 浏览右侧宽度：保留 `browserRightPaneWidth` 拖拽。
    - ER右侧宽度：保留 `erRightPaneWidth` 拖拽。
    - 查询右侧宽度：`queryRightPaneWidth` + `startResizeQueryPane`。
  - `workbenchStyle` 改为 5 列布局（左区 + 左分割条 + 中区 + 右分割条 + 右区），并按当前工作台动态套用可拖拽宽度。
  - 组件卸载时补充新增拖拽事件监听清理，避免内存泄漏。
- 修改文件：`apps/desktop/src/style.css`
  - 新增分割条布局规则：`.pane-splitter-left`、`.query-pane-splitter`。
  - 查询页网格位重新映射：
    - `query-shared-meta` 改为跨 `3/6`。
    - `query-editor-pane` 改为第 3 列。
    - `query-chat-pane` 改为第 5 列。
    - `query-pane-splitter` 位于第 4 列。
  - 移动端（`max-width:1200px`）隐藏分割条，保持单列布局体验。
  - 工作区列间距改为 `column-gap: 0`，横向间隔由分割条宽度统一控制。

## 验证结果
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081` 启动成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6046` 启动成功，`HTTP/1.1 200 OK`。
