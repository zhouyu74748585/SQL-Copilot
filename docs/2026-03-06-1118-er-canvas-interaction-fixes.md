# ER画布交互修复记录（2026-03-06 11:18）

## 本轮目标
- hover 连线时高亮对应两端字段。
- 连线连接点不再强制绑定字段行位置。
- 支持拖动连线中间折点与两端连接点。
- 点击画布空白处取消连线选中。
- 连线统一为实线。

## 实现内容
- 画布组件：`apps/desktop/src/components/ErDiagramPanel.vue`
  - 字段行增加关系端点高亮状态映射（按 `relationKey + table + column`）。
  - 连线锚点改为几何自适应（基于对端中心 + 可控抖动），不再依赖字段行索引。
  - 新增连接点手柄（source/target），可沿卡片边缘上下拖动；保留 laneX 中间手柄拖动。
  - 新增连接点拖动状态 `route-anchor` 与对应拖拽逻辑，拖拽时实时重绘线条。
  - 空白区域点击时清空 `activeRelationKey`/`hoveredRelationKey`。
  - `dashArray` 统一置空，AI/FK 都使用实线。

## 验证结果
- 前端类型检查：`cd apps/desktop && npm run type-check` 通过。
- 前端构建：`cd apps/desktop && npm run build` 通过。
- 后端 clean 打包：`cd apps/server && mvn clean package` 通过。
- 后端启动验证：`mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18081` 启动成功（18080 被本机已有进程占用）。
- 前端预览验证：`npm run preview -- --host 127.0.0.1 --port 6044` 可启动（端口占用时自动切到 6045）。

## 追加记录（2026-03-06 12:41）- ER画布固定逻辑尺寸

### 本次目标
- ER 图画布尺寸固定，不随应用窗口大小变化而触发重新布局。
- 窗口大小变化仅影响可视范围（裁剪/显示区域），不改变节点布局。
- 保留画布缩放能力（滚轮放大缩小）。

### 关键改动
- 修改文件：`apps/desktop/src/components/ErDiagramPanel.vue`
- 新增固定逻辑画布状态：
  - 新增 `LayoutCanvasState` 与 `layoutCanvas`（`width/height`）。
  - 在首次 `initViewportSize()` 时锁定 `layoutCanvas` 尺寸（最小 `640x420`），后续 resize 不再更新该逻辑尺寸。
- 布局计算改为基于固定逻辑尺寸：
  - `defaultNodeCenters` 从使用 `viewport.width/height` 改为使用 `layoutCanvas.width/height`。
- 关系同侧锚点判定改为固定逻辑中心：
  - `sameSideAnchorOnRight` 从 `viewport.width / 2` 改为 `layoutCanvas.width / 2`，避免窗口变化导致连线几何重算。
- 保留缩放和平移交互：
  - `onViewportWheel`、拖拽平移逻辑未改，放大缩小能力保持不变。

### 验证结果
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081` 启动成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6046` 启动成功，`HTTP/1.1 200 OK`。

## 追加记录（2026-03-06 12:51）- 连接点支持沿卡片四边整圈拖动

### 本次目标
- 连线与卡片的连接点支持在卡片周围一圈拖动（不再仅限上下方向）。

### 关键改动
- 修改文件：`apps/desktop/src/components/ErDiagramPanel.vue`
- 锚点数据结构升级：
  - `RouteAnchorOffsets` 由 `sourceOffsetY/targetOffsetY` 改为 `sourcePerimeterPos/targetPerimeterPos`（0~1 周长归一化位置）。
- 新增周长锚点计算：
  - `resolvePointByPerimeterPos`：将周长位置映射为卡片四边上的实际连接点坐标。
  - `projectPointToTablePerimeter`：拖拽时将鼠标点投影到卡片最近边界，并计算周长位置。
- 连线端点计算调整：
  - 关系渲染优先使用手动 `perimeterPos` 作为 source/target 连接点；未手动时保持原自动锚点策略。
- 拖拽逻辑调整：
  - 端点手柄拖拽时实时投影到卡片四边，更新 `sourcePerimeterPos/targetPerimeterPos`。
- 交互样式微调：
  - 端点手柄光标由 `ns-resize` 改为 `move`，匹配“整圈拖动”语义。

### 验证结果
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081` 启动成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6046` 启动成功，`HTTP/1.1 200 OK`。

## 追加记录（2026-03-06 13:08）- ER连线线型切换（直线/折线）

### 本次目标
- 增加 ER 连线线型选择，支持“直线”和“折线”两种模式。

### 关键改动
- 修改文件：`apps/desktop/src/App.vue`
  - ER 工具栏新增“线型”下拉框，选项为“折线（POLYLINE）/直线（STRAIGHT）”。
  - `ErWorkspaceTab` 新增 `lineType` 字段，标签级维护线型状态。
  - 新建 ER 标签与快照恢复标签统一初始化 `lineType='POLYLINE'`，历史标签补默认值。
  - 向 ER 画布组件透传 `line-type` 属性。
- 修改文件：`apps/desktop/src/components/ErDiagramPanel.vue`
  - 新增 `lineType` 属性（默认 `POLYLINE`）。
  - 连线点位按模式分支：
    - `STRAIGHT`：两点直连（source -> target）。
    - `POLYLINE`：保留正交多段折线路由（含锚点外引导段与中间 lane）。
  - 直线模式下隐藏中间路由拖拽点，并禁用按线拖拽 lane；两端锚点拖动仍可用。
  - 置信度标签位置在直线模式下改为中点附近，在折线模式保持 lane 区域。

### 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081` 启动成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6046` 启动成功，HTTP 状态码 `200`。

## 追加记录（2026-03-06 13:13）- 注释模式字段注释展示与卡片宽度控制

### 本次目标
- 开启“显示注释”时，字段注释在卡片字段行中一并展示。
- 同时控制卡片宽度，避免注释开启后布局过窄或无限拉宽。

### 关键改动
- 修改文件：`apps/desktop/src/components/ErDiagramPanel.vue`
- 字段注释展示：
  - 字段行在 `showComments=true` 时始终渲染注释列。
  - 无注释字段显示占位 `-`，避免列对齐抖动。
- 卡片宽度策略：
  - 新增两档卡片宽度：普通模式 `226`，注释模式 `320`。
  - 通过 `nodeCardWidth()` 统一驱动卡片宽度，并同步到节点布局计算。
- 布局联动：
  - 网格/环形布局改为使用当前卡片宽度参与排布与半径计算。
  - 连线同侧锚点判定阈值改为基于实际表卡宽度（不再使用固定宽度常量）。
- 样式微调：
  - 注释模式下字段行列宽重分配，给注释列更多可见空间，并保持省略号截断。

### 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081` 启动成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6046` 启动成功，HTTP 状态码 `200`。
