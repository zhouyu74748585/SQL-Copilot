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
- 前端预览验证：`npm run preview -- --host 127.0.0.1 --port 8888` 可启动（端口占用时自动切到 6045）。

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

## 追加记录（2026-03-12 15:46）- ER 连线加号去圈与折叠字段处理

### 本次目标
- 调整 ER 图字段连线入口样式，去掉 `+` 图标外圈。
- 处理大表字段折叠后隐藏字段不易发现、无法直观发起连线的问题。

### 关键改动
- 修改文件：`apps/desktop/src/components/ErDiagramPanel.vue`
  - 将字段连线手柄从“圆形按钮”改为裸 `+` 图标，仅保留文字命中区与 hover 高亮，不再显示外围圆圈。
  - 为字段过多的表卡增加“展开/收起字段”行，折叠态显示“还有 N 个字段未显示”，点击后可展开完整字段列表，便于从隐藏字段发起手工连线。
  - 新增表级展开状态 `expandedTableState`，折叠/展开会实时重算卡片高度与默认布局高度。
  - 折叠态下若当前高亮关系命中了隐藏字段，会将“更多字段”聚合行标记为关联提示态，避免用户误以为关系丢失。
  - 手工连线锚点解析补充隐藏字段兜底：当目标字段在折叠区时，聚合到“更多字段”行；展开后则恢复到真实字段行。
  - 导出 PNG 时同步绘制新的“展开/收起字段”聚合行文案。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端构建：`npm run build -- --emptyOutDir` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18091"` 启动成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6065 --strictPort` 启动成功，HTTP 状态码 `200`。

## 追加记录（2026-03-12 11:58）- ER快照绝对布局持久化

### 本次目标
- 修复 ER 图快照/历史回显时节点布局随当前窗口尺寸被重新挤压的问题。
- 保证 ER 图保存的是固定逻辑画布与绝对位置；窗口变小后仅裁剪显示，不自动重排。
- 保持“仅手动调整或切换布局模式时才改变布局”的交互语义。

### 关键改动
- 修改文件：`apps/desktop/src/components/ErDiagramPanel.vue`
  - 新增 `graph-layout-change` 事件，向父级回传固定逻辑画布尺寸、手动节点坐标、连线端点锚点偏移。
  - 在图数据/布局模式变化时优先从快照中的 `layoutCanvas/nodePositions/relationAnchorOffsets` 恢复画布状态；缺失时才按当前视口初始化。
  - 布局模式或表集合变化时清空旧画布状态，避免旧布局污染新模式；窗口 resize 仍只影响可视范围，不影响逻辑布局。
- 修改文件：`apps/desktop/src/modules/studio/composables/useErModule.ts`
  - 新增 `handleErGraphLayoutChange()`，把画布回传的布局状态持久化到 `tab.graph`，用于快照保存与历史回显。
  - 新增 `handleErLayoutModeChange()`，切换布局模式时清空旧的节点坐标、锚点偏移和手动路由，使新模式重新布局。
- 修改文件：`apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - ER 图刷新时合并保留已有的 `layoutCanvas/nodePositions/relationAnchorOffsets` 与手动折线路由，避免刷新后本地布局状态丢失。
- 修改文件：`apps/desktop/src/modules/studio/components/StudioShell.vue`
  - ER 布局选择改为显式调用 `handleErLayoutModeChange()`。
  - ER 画布接入 `graph-layout-change` 事件，确保当前布局实时回写到工作台图数据。
- 修改文件：`apps/desktop/src/types/index.ts`
  - 扩展 `ErGraphVO`，加入 `layoutCanvas`、`nodePositions`、`relationAnchorOffsets`。
- 修改文件：`apps/server/src/main/java/com/sqlcopilot/studio/dto/schema/ErGraphVO.java`
  - 后端快照 DTO 同步扩展布局字段。
- 新增文件：
  - `apps/server/src/main/java/com/sqlcopilot/studio/dto/schema/ErLayoutCanvasVO.java`
  - `apps/server/src/main/java/com/sqlcopilot/studio/dto/schema/ErNodePositionVO.java`
  - `apps/server/src/main/java/com/sqlcopilot/studio/dto/schema/ErRelationAnchorOffsetVO.java`

### 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 前端 clean 构建 + 预览：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后，`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4174` 启动成功；`curl -I http://127.0.0.1:4174` 返回 `HTTP/1.1 200 OK`。
- 后端 Maven：`mvn -f apps/server/pom.xml -DskipTests clean package` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18082"` 启动成功；`curl http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 额外说明：`mvn -f apps/server/pom.xml clean package` 仍被现有测试阻塞，失败用例为 `AiServiceImplAstValidationTest.buildRepairPrompt_keepsOnlyDynamicRepairContext` 与 `OnnxLocalRerankServiceImplTest.score_acceptsCrossEncoderModelInputs`；本次未修改对应业务代码。

### 遗留项
- 当前快照仍未保存 ER 页签的线型与“显示注释”开关，仅修复了布局绝对坐标与固定逻辑画布的持久化。

## 追加记录（2026-03-12 14:59）- ER手工字段连线与统一删除

### 本次目标
- 支持在 ER 图中从字段手工拉线，连接两个表中的字段。
- 支持所有连线在选中状态下通过 Delete 键删除，或右键菜单删除。
- 保证 ER 图中的新增、删除、布局操作仅影响当前页签和快照，不影响数据库真实元数据。

### 关键改动
- 修改文件：`apps/desktop/src/components/ErDiagramPanel.vue`
  - 新增字段级拉线手柄、手工连线预览、关系右键删除菜单。
  - 新增受控选中关系能力，支持父级维护 `selectedRelationKey`。
  - 新增 `relation-select`、`relation-delete-request`、`manual-relation-create` 事件。
  - 手工拉线时校验目标字段必须属于另一张表，避免自连。
- 修改文件：`apps/desktop/src/modules/studio/composables/useErModule.ts`
  - 统一管理 FK / AI / MANUAL 三类关系的选中、创建、删除。
  - 删除关系时直接从当前图数据移除，并同步清理锚点偏移和选中态。
  - 新增全局 Delete 快捷键处理，仅在 ER 页签且焦点不在输入控件时生效。
- 修改文件：`apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - `ErWorkspaceTab` 新增 `selectedRelationKey`。
  - `ErGraphVO` 前端运行态新增 `manualRelations`，ER 刷新后保留手工关系及已有布局路由状态。
  - 新增 `activeErManualRelations` 供右侧信息区展示。
- 修改文件：`apps/desktop/src/modules/studio/components/StudioShell.vue`
  - ER 画布接入受控选中、手工连线创建、删除请求事件。
  - 右侧信息区新增“手工连线”分组；FK / AI / 手工三组均支持删除按钮和选中联动。
- 修改文件：`apps/desktop/src/modules/studio/styles/shell.css`
  - 新增手工关系卡片和选中态样式。
- 修改文件：`apps/desktop/src/types/index.ts`
  - `ErRelationVO.relationType` 增加 `MANUAL`。
  - `ErGraphVO` 增加 `manualRelations`。
- 修改文件：`apps/server/src/main/java/com/sqlcopilot/studio/dto/schema/ErGraphVO.java`
  - 后端快照 DTO 同步增加 `manualRelations`，确保手工关系可随快照持久化。
- 修改文件：`apps/server/src/main/java/com/sqlcopilot/studio/service/impl/ErDiagramServiceImpl.java`
  - ER 初始图返回空的 `manualRelations`，保持前后端结构一致。

### 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 后端 clean 打包：`mvn -f apps/server/pom.xml -DskipTests clean package` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml -DskipTests clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18083"` 启动成功；`curl http://127.0.0.1:18083/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端 clean 构建 + 预览：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后，`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4175` 启动成功；`curl -I http://127.0.0.1:4175` 返回 `HTTP/1.1 200 OK`。

### 额外说明
- `mvn -f apps/server/pom.xml clean package` 仍被现有测试阻塞，失败用例为：
  - `AiServiceImplAstValidationTest.buildRepairPrompt_keepsOnlyDynamicRepairContext`
  - `OnnxLocalRerankServiceImplTest.score_acceptsCrossEncoderModelInputs`
- 上述失败与本次 ER 图改动无直接关联；本次已通过 `-DskipTests` clean package 和 clean 启动完成交付验证。

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
