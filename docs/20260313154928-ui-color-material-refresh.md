# 主题：ui-color-material-refresh

## 记录

### 2026-03-13 15:49:28

## 本次目标
- 在不改布局、分割条和交互结构的前提下，优化桌面端整体配色。
- 在用户允许范围内，增加轻量阴影与轻量材质，让界面更现代、更有科技感，但保持克制。

## 关键改动
- 前端 `apps/desktop/src/modules/studio/styles/shell.css`
  - 仅调整根级主题色板，重做 light/dark 两套颜色变量：主色改为更克制的蓝色系，并补充青绿色成功态、暖橙告警态、玫瑰红错误态。
  - 同步更新页面背景渐变、消息气泡渐变、文本层级色和边框色，让整套工作台从偏灰蓝改为更干净的科技蓝青风格。
- 前端 `apps/desktop/src/style.css`
  - 新增全局样式覆盖，但只作用于颜色、阴影和材质表现，不改尺寸、布局与拖拽逻辑。
  - 为顶部 chrome、操作按钮、Tab、主面板、弹层、聊天卡片、输入框等加入轻量玻璃感背景、柔和阴影和浅层模糊。
  - 所有效果限定在面板容器本身，没有新增大面积光斑或查询区特殊伪元素。
- 前端 `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 调整 Ant Design Vue `ConfigProvider` 主题 token，统一 `primary/info/success/warning/error` 色系，确保 Ant 组件与自定义样式配色一致。

## 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端 clean 构建：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18121' '-Dfile.encoding=UTF-8'` 成功，`http://127.0.0.1:18121/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6071 --strictPort` 成功，`http://127.0.0.1:6071/` 返回 `HTTP/1.1 200 OK`。

## 说明
- 本次未改动分割条、拖拽区域和查询页结构逻辑，重点限制在配色、阴影与材质表达。


### 2026-03-13 15:52:23

## 本次目标
- 去掉本轮新增材质效果带来的新增圆角，避免与现有直角界面混用产生割裂感。
- 保留已经调整过的配色、阴影和材质方向，但不再新增圆角表现。

## 关键改动
- 前端 `apps/desktop/src/style.css`
  - 删除本轮新增面板/弹层材质覆盖中的 `border-radius: 14px`，让新增材质层回归直角。
- 前端 `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 将 Ant Design Vue 主题 token `borderRadius` 从 `10` 调整为 `0`，减少 Ant 组件继续引入新的圆角。

## 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端 clean 构建：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18122' '-Dfile.encoding=UTF-8'` 成功，`http://127.0.0.1:18122/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6072 --strictPort` 成功，`http://127.0.0.1:6072/` 返回 `HTTP/1.1 200 OK`。
