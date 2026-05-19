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


### 2026-03-11 11:00:34

## 本次目标
- 将 AI 会话改为 POST + SSE 流式传输，覆盖 Auto、生成 SQL、解释 SQL、分析 SQL、图表方案、SQL 修复。
- 在对话消息中新增原始 Thinking 展示，并保留折叠式请求详情/Trace 调试面板。
- 保持同步接口兼容，同时将桌面端默认切到流式接口。

## 关键改动
- 后端新增 6 个 SSE 流式接口：`/api/ai/query/{auto|generate|explain|analyze|generate-chart|repair}/stream`。
- 新增流式 DTO：`AiStreamEventVO`、`AiStreamDeltaVO`、`AiStreamFinalVO`、`AiStreamErrorVO`、`AiStreamIntentVO`。
- 新增 `AiStreamObserver` / `SseAiStreamObserver`，在 `AiServiceImpl` 内通过统一 observer 上下文复用原有业务流程，并在阶段完成时实时推送 `stage.updated`、`trace.snapshot`、`result.final`。
- `LlmGatewayService` 改为统一走流式网关；`OpenAiTextClient` 新增 Responses API / Chat Completions SSE 解析，并透传 provider thinking、request id、streaming 标记。
- `AiTraceLlmCallVO` / `LlmGatewayResult` 扩展了 `thinkingContent`、`providerRequestId`、`streaming`，并继续通过 `traceJson` 持久化到历史。
- 前端新增 `postSseApi` 与 `postAiStreamWithTimeout`，默认用 `fetch + ReadableStream` 解析 POST SSE。
- `useStudioRuntime.ts` 将 explain/analyze/generate/auto/chart-plan/repair 全部切换到流式消费；消息新增 `streaming`、`finalized`、`thinkingContent`、`liveOutput`、`aborted` 状态。
- 查询聊天 UI 增加 Thinking 面板、流式状态标记，并在调试详情中展示 provider request id 与 thinking 内容；历史恢复时可从 trace 中回填 Thinking。

## 验证结果
- 后端构建：`mvn -f apps/server/pom.xml clean package` 通过。
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18088` 启动成功，`http://127.0.0.1:18088/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端 clean 预览：先清理 `apps/desktop/dist`，再执行 `npm run -w @sqlcopilot/desktop build` 和 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6047`，`http://127.0.0.1:6047` 返回 `HTTP/1.1 200 OK`。

## 备注
- 当前 Thinking 严格依赖 provider 实际返回；若模型或 CLI 不提供 reasoning/thinking，界面不会伪造思考内容。
- Vite 构建仍有大 chunk 警告，但不影响本次功能交付与启动验证。

### 2026-05-19 09:33

## 追加记录 - Thinking 内容折叠功能

### 本次目标
- 前端对话框中 AI 助手的 Thinking 内容面板支持点击标题折叠/展开，提升对话界面可读性。

### 关键改动
- 新增 `thinkingExpanded` 字段到 `QueryChatMessage` 接口（`types.ts`），控制 Thinking 面板的展开/折叠状态。
- 新增 `toggleMessageThinkingExpanded` 方法（`query-chat.ts`），用于切换单条消息的 Thinking 折叠状态。
- 在 `state.ts` 中导出该方法，并在 `StudioShell.vue` 中集成。
- 模板改造：将 Thinking 标题从 `<div>` 改为 `<button>` 按钮，点击触发折叠切换；箭头图标 ▸/▾ 表示当前状态。
- Thinking 内容 `<pre>` 通过 `v-if="item.thinkingExpanded !== false"` 控制显隐。
- CSS 新增 `.query-chat-thinking-toggle` 系列样式（按钮、标签、箭头图标、hover 效果），并在暗色/亮色主题中适配。
- 流式场景：`applyStreamTraceSnapshot` 和 `prepareAssistantMessage` 中设置 `thinkingExpanded = true`（默认展开，方便实时查看）。
- 历史加载：`useHistoryModule.ts` 中设置 `thinkingExpanded = false`（历史消息默认折叠，减少界面干扰）。

### 验证结果
- 前端类型检查：`apps/desktop` 下 `npm run type-check` 通过。
- 前端构建：`apps/desktop` 下 `npm run build` 通过。
- 后端 clean 编译：`apps/server` 下 `mvn clean compile` 通过。
- 启动验证：后端（8080）+ 前端（8888）均正常启动，页面可访问。

### 备注
- 箭头使用 Unicode 字符 ▸/▾，不依赖额外图标库。
- 默认展开逻辑：流式对话中 Thinking 实时产生时默认展开；历史会话加载时默认折叠。
