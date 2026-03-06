# OpenAI Token Usage 统一统计改造记录

## 本次目标

将 `OpenAiTextClient` 从仅返回文本改造为统一返回“文本 + token 使用量”，并在上层 `AiServiceImpl` 中优先使用真实 usage 进行回填，估算仅作为兜底。

## 主要改动

1. `OpenAiTextClient` 返回结构统一为 `OpenAiTextResult`：
   - `content`：模型输出文本
   - `usage`：`TokenUsage(promptTokens, completionTokens, totalTokens, estimated)`

2. 在客户端内统一完成 token usage 解析：
   - Chat Completions：读取 `usage.prompt_tokens/completion_tokens/total_tokens`
   - Responses API：读取 `usage.input_tokens/output_tokens/total_tokens`
   - SSE 场景：从 `response.completed` 事件中的 `response.usage` 解析

3. usage 兜底策略：
   - 当接口未返回 usage 时，按统一估算规则补齐：
   - `estimateTokens = ceil(trim(text).length / 4.0)`
   - `prompt = estimate(systemPrompt + "\n" + userPrompt)`
   - `completion = estimate(content)`
   - `total = prompt + completion`
   - 并标记 `estimated=true`

4. `AiServiceImpl` 统一接入 usage：
   - OpenAI 路径：通过 `ProviderResult/TextProviderResult` 传递 usage 到 VO 层
   - CLI 路径：usage 为空，保持原估算逻辑
   - 新增 `resolveTokenUsage(...)`，统一“真实值优先、估算兜底”

5. 兼容性修复：
   - 同步修复 `RagIngestionServiceImpl` 对 `openAiTextClient.requestText(...)` 的调用，改为读取 `result.content()`

## 验证结果

- 已完成：`apps/server` 编译验证（`mvn -DskipTests compile` 成功）
- 启动验证：尝试 `mvn spring-boot:run -DskipTests`，进程启动到 Web 容器阶段后失败，原因为 `18080` 端口已被占用（非本次改造代码错误）
- clean 验证：`mvn clean` 遇到 `target/test-classes/...SqlCopilotApplicationTests.class` 文件删除权限问题，需释放文件占用后重试

## 增量更新（CLI Token 统计接入）

根据后续需求追加了 CLI 口径的 token 结构化解析与回填：

1. 在 `AiServiceImpl` 中新增 CLI usage 解析能力（codex 场景）：
   - 识别 `tokens used` 区块
   - 支持 `input/prompt/output/completion/total` 键值行解析
   - 支持纯数字行（1/2/3 行）解析

2. 将 usage 透传到所有 CLI 返回路径：
   - `generateRawTextByLocalCli`
   - `generateByLocalCli`
   - `generateTextByLocalCli`
   - 均改为返回 `ProviderResult/TextProviderResult(..., usage)`

3. 缺失字段兜底策略：
   - 若仅拿到 `totalTokens`，则用 prompt/completion 估算比例拆分
   - 若 usage 不可解析则返回 `null`，由上层统一估算兜底

4. 本次追加验证：
   - `mvn -DskipTests compile` 成功
   - `mvn spring-boot:run -DskipTests` 仍因 `18080` 端口占用失败（环境占用问题）

## 增量更新（前端 Auto 模式 token 刷新）

为修复 Auto 模式 UI 的 `≈Token` 不刷新的问题，在 `apps/desktop/src/App.vue` 中补充了回填逻辑：

1. 在 `sendAutoForTab(...)` 的 `/api/ai/query/auto` 成功返回后，立即读取 `result.totalTokens`；
2. 若返回值存在，则更新 `tab.lastTokenEstimate`；
3. 这样后续 Auto 分支（生成 SQL / 图表方案 / 解释 / 分析）以及会话保存逻辑均会使用本次请求的最新 token 值，而不是沿用旧值。

### 本次前端验证

- `npm run type-check`（`apps/desktop`）成功，无新增类型错误。

## 增量更新（会话累计 Token 记录）

按“每个会话记录当前会话使用的总 token 数”的需求，补齐了会话维度统计与展示：

1. 后端会话分页汇总补充 `totalTokens`：
   - `QueryHistorySessionVO` 新增 `totalTokens` 字段；
   - `QueryHistoryMapper.pageSessions` 在会话分组层增加 `SUM(COALESCE(q.token_estimate, 0)) AS totalTokens`。

2. Auto 接口补充总 token：
   - `AiAutoQueryVO` 新增 `totalTokens`；
   - `AiServiceImpl.autoQuery(...)` 在路由到 `generate/explain/analyze/generateChart` 后，将对应结果的 `totalTokens` 回填到 Auto 响应。

3. 前端会话历史显示累计 token：
   - `apps/desktop/src/types/index.ts` 的 `QueryHistorySessionVO` 新增 `totalTokens?: number`；
   - 会话历史列表元信息新增展示：`累计Token: {{ item.totalTokens ?? 0 }}`。

4. 修正历史保存 token 的取值时机（避免写入旧值）：
   - 在多处 AI 请求成功分支中，先更新 `tab.lastTokenEstimate = result.totalTokens`；
   - 再调用 `saveConversationHistoryOnce(..., { tokenEstimate: tab.lastTokenEstimate })`；
   - 覆盖普通模式（生成/解释/分析/图表）与 Auto 模式，保证会话累计统计基于本次真实返回值。

### 本次验证

- 后端：`mvn -DskipTests compile` 成功。
- 前端：`npm run type-check` 成功；`npm run build` 成功。
- 前端预览：通过 `npx vite preview --host 127.0.0.1 --port 4173` 启动成功（验证后已停止）。
- 后端启动：`mvn spring-boot:run -DskipTests` 仍受环境端口占用影响（`18080` 已被占用）。

