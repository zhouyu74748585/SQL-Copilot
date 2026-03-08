# 主题：query-chat-layout-width-guard

## 记录

### 2026-03-08 21:06:38

### 本次目标
- 优化 AI 对话输入区在小尺寸下的拥挤问题，减少开关和选择器同时直出的数量。
- 保留当前模型展示的紧凑样式，同时支持直接下拉切换模型。
- 避免桌面窗口宽度小于 1200px 后工作台从左右三栏退化为上下三块。

### 关键改动
- 对话输入区重构为“状态信息 + 设置入口 + 动作按钮”：
  - 将 `Auto`、详情输出、长对话、自动执行收纳进设置弹层。
  - 模型区域改为紧凑胶囊样式，点击后通过下拉菜单切换模型。
  - 收紧模型与 `Auto` 之间的间距，降低窄宽度下被挤换行的概率。
- 更新对话输入区样式：
  - 新增模型胶囊、状态胶囊、设置面板样式。
  - 调整元信息区域的 `gap` 和 `flex` 行为，避免模型区域过度占宽。
  - 保留旧控件绑定逻辑，但在新布局下隐藏冗余直出控件。
- 为 Electron 主窗口增加尺寸下限：
  - `minWidth: 1200`
  - `minHeight: 760`
  - 直接阻止进入当前未完善的窄屏三段式降级布局。

### 涉及文件
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
- `apps/desktop/src/modules/studio/styles/shell.css`
- `apps/desktop/electron/main.cjs`

### 验证结果
- 前端类型检查：
  - `npm run type-check` 通过。
- 前端构建：
  - `npm run build` 通过。
- 启动验证：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.mainClass=com.sqlcopilot.studio.SqlCopilotApplication" "-Dspring-boot.run.arguments=--server.port=18090"` 后，`http://127.0.0.1:18090/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run build` 后，`npx vite preview --host 127.0.0.1 --port 18091` 可访问，`http://127.0.0.1:18091` 返回 `200`。

### 备注
- 当前环境下部分端口会触发 `EACCES / WinError 10013`，因此前端预览改用 `18091` 完成验证。

### 2026-03-08 21:16:00

### 追加记录：Token 显示迁移到对话标题栏

### 本次目标
- 将对话输入区中的 token 消耗显示移到对话标题栏最右侧，减少输入区视觉负担。

### 关键改动
- 在对话面板标题区新增右侧 token 显示，使用紧凑胶囊样式承载 `lastTokenEstimate`。
- 输入区保留原有状态布局，但隐藏原 token 胶囊，避免重复显示。
- 保持模型切换、Auto、设置入口和动作按钮的现有排列不变。

### 验证结果
- `npm run type-check` 通过。
- `npm run build` 通过。
- 后端健康检查仍通过：`http://127.0.0.1:18090/api/health`
- 前端预览仍可访问：`http://127.0.0.1:18091`
