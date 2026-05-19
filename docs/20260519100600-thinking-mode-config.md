# 思考模式配置功能

## 概述

为 AI 对话添加思考模式（Thinking）开关配置项，默认关闭。用户可以在设置中开启思考模式，以查看 AI 模型的推理过程。

## 修改的文件

### 后端

#### `apps/server/src/main/java/com/sqlcopilot/studio/dto/ai/AiGenerateSqlReq.java`
- 新增 `thinkingEnabled` 字段，接收前端传递的思考模式开关状态

#### `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/DisabledThinkingLlmStreamListener.java`（新建）
- 实现 `LlmStreamListener` 接口
- 当 thinkingEnabled=false 时，跳过所有 thinking delta 事件，只转发 output delta 事件
- 关键实现：
  - `onThinkingDelta`: 静默忽略，不发送任何事件
  - `onOutputDelta`: 正常转发 output delta 事件

#### `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/AiServiceImpl.java`
- 修改 `createLlmStreamListener` 方法，增加 `thinkingEnabled` 参数
- 根据 `thinkingEnabled` 选择不同的监听器：
  - `true`: 使用 `CancellableLlmStreamListener`（支持 SSE 失败优雅降级）
  - `false`: 使用 `DisabledThinkingLlmStreamListener`（禁用思考模式）

### 前端

#### `apps/desktop/src/modules/studio/composables/useStudioRuntime/constants.ts`
- 新增 `thinkingEnabledStorageKey` 常量

#### `apps/desktop/src/modules/studio/composables/useStudioRuntime/state.ts`
- 新增 `thinkingEnabled` 响应式状态（默认 `false`）
- 在导出对象中添加 `thinkingEnabled` 和 `thinkingEnabledStorageKey`
- 在 `createAiInteractionHelpers` 参数中添加 `getThinkingEnabled` 函数

#### `apps/desktop/src/modules/studio/composables/useUiShellModule.ts`
- `UiShellModule` 接口新增方法：
  - `toggleThinkingEnabled`: 切换思考模式
  - `loadThinkingEnabledPreference`: 从 localStorage 加载配置
  - `persistThinkingEnabledPreference`: 保存配置到 localStorage
- 在 `onMounted` 中调用 `loadThinkingEnabledPreference`
- 添加 `watch` 监听 `thinkingEnabled` 变化，自动持久化

#### `apps/desktop/src/modules/studio/composables/useStudioRuntime/ai-interaction.ts`
- `AiInteractionHelperContext` 接口新增 `getThinkingEnabled` 方法
- 所有 AI 流式请求（generate/explain/analyze/chart/auto/repair）中添加 `thinkingEnabled` 参数

#### `apps/desktop/src/modules/studio/components/StudioShell.vue`
- 在设置弹窗的**模型配置**页面添加思考模式开关（位于"默认输出详情"旁边）
- 导入 `thinkingEnabled` 和 `toggleThinkingEnabled`

#### `apps/desktop/src/i18n/messages.ts`
- 新增翻译：`'思考模式': 'Thinking Mode'`

## 验证结果

| 验证项 | 结果 |
|--------|------|
| `npm run type-check` (apps/desktop) | ✅ 通过 |
| `npm run build` (apps/desktop) | ✅ 通过 |
| `mvn clean compile` (apps/server) | ✅ 通过 |

## 功能说明

### 行为

- **默认状态**：思考模式默认关闭（`thinkingEnabled = false`）
- **开启后**：AI 模型的思考推理过程会通过 SSE 事件 `llm.thinking.delta` 实时推送到前端显示
- **关闭后**：只接收 `llm.output.delta` 事件，不显示思考过程，响应更简洁

### 适用场景

- **开启**：需要了解 AI 推理过程、分析模型行为时
- **关闭**：减少网络流量、加快响应速度，尤其在 Auto 模式下效果更明显（减少 Broken pipe 问题）

### 特殊规则

- **意图识别（Auto 模式）**：固定关闭思考模式，无论用户设置如何，始终传递 `thinkingEnabled: false`
  - 原因：意图识别是轻量级快速操作，不需要展示思考过程
  - 涉及的 API：`/api/ai/query/auto/stream`

## 更新记录

| 日期 | 更新内容 |
|------|----------|
| 2026-05-19 10:14 | 意图识别（auto）固定关闭思考模式 |
