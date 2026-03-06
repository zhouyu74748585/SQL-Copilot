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
