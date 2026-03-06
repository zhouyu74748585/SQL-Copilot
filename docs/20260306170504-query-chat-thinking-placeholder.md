# 主题：query-chat-thinking-placeholder

## 记录

### 2026-03-06 17:05:04

## 追加记录（2026-03-06 17:06）- 对话等待态占位回复

### 本次目标
- 在查询对话框中，当 AI 请求发送后、后端响应返回前，先展示一条“思考中...”助手占位回复。
- 后端返回后，使用真实回复原位替换占位消息，避免界面空窗。

### 关键改动
- 前端对话消息结构新增 `pending` 标记，用于表示占位态。
- 新增消息辅助流程：
  - `appendAssistantThinkingMessage`：请求发起时插入“思考中...”占位回复。
  - `appendAssistantTextMessage/appendAssistantSqlMessage` 扩展支持“目标消息替换”，用于将占位消息原位替换为正式回复。
  - `removeQueryChatMessage`：请求失败/终止时清理占位消息，避免遗留“思考中...”。
- 将占位替换逻辑接入以下链路：
  - 解释/分析 SQL 对话链路。
  - 生成 SQL 链路。
  - Auto 意图链路（生成 SQL、解释/分析、图表计划）。
  - 图表计划生成链路。
  - SQL 修复链路。
- UI 样式调整：
  - 对占位回复增加 `is-thinking` 样式与旋转图标，增强等待态可感知性。

### 验证结果
- 前端构建验证（`apps/desktop`）：
  - `npm run build` 通过。
  - `npm run type-check` 通过。
- 启动验证（按 clean 要求）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18086`，健康检查 `http://127.0.0.1:18086/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：先 `rm -rf apps/desktop/dist`，再 `npm run build` 与 `npm run preview -- --host 127.0.0.1 --port 6045`，`http://127.0.0.1:6045` 可访问。

### 备注
- 本次仅调整前端对话渲染与请求等待态，不涉及旧数据兼容处理。


### 2026-03-06 17:09:09

## 追加记录（2026-03-06 17:18）- 禁止 Auto 自动拼接编辑器 SQL

### 本次目标
- 修复 Auto 模式错误拼接编辑器 SQL 的问题。
- 约束为：仅当用户手动在编辑器中选择 SQL 后触发时，才允许将 SQL 片段拼接进提示词。

### 关键改动
- 新增 `resolveSelectedSqlSnippet(tab, sqlOverride?)`：
  - 仅返回 `sqlOverride` 或 `tab.selectedSqlText`。
  - 不再回退到 `tab.sqlText`。
- 调整 AI 对话请求拼接逻辑：
  - `generateSqlForTab` 中 explain/analyze 的 `actionSqlSnippet` 改为仅取手动选择片段。
  - `sendAutoForTab` 中 `sqlSnippet` 改为仅取手动选择片段。
- 保留 `resolveSqlForAction` 原行为，仅用于执行/解释等 SQL 执行动作，不影响编辑器主执行体验。

### 验证结果
- 前端构建与类型检查：
  - `npm run build`（apps/desktop）通过。
  - `npm run type-check`（apps/desktop）通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18087` 启动成功；`http://127.0.0.1:18087/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`rm -rf apps/desktop/dist` 后执行 `npm run build` 与 `npm run preview -- --host 127.0.0.1 --port 6046`，页面可访问。
