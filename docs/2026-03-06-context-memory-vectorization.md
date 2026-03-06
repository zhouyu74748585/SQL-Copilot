# 2026-03-06 上下文记忆与滑动窗口压缩开发记录

## 背景
- 目标：新增可控的对话记忆能力，引入滑动窗口压缩、结构化上下文、压缩后向量化入库检索、会话删除联动清理，以及粗略 token 统计。

## 本次实现

### 1) 会话记忆配置（全局）
- 在 AI 配置中新增：
  - `conversationMemoryEnabled`（默认开启）
  - `conversationMemoryWindowSize`（默认 12，范围 4~50）
- 后端配置持久化与迁移已补齐字段，前端配置弹窗可直接设置。

### 2) 对话级记忆开关（每个会话）
- 查询工作台新增“记忆”开关，按会话控制是否启用记忆。
- 调用 AI 接口时透传 `memoryEnabled`，后端优先使用请求级开关。

### 3) 滑动窗口压缩 + 结构化上下文
- 后端在构建生成上下文时：
  - 读取当前会话历史；
  - 保留最近 N 轮（N=窗口大小）作为结构化 JSON 上下文；
  - 对更早历史做压缩摘要；
  - 将“向量召回记忆 + 压缩摘要 + 窗口 JSON + 原 RAG/Schema 上下文”拼接为最终上下文。

### 4) 压缩后向量化入库 + 对话前检索
- 每次触发压缩后，将压缩摘要向量化并写入 `sql_history` 集合（payload 含 `session_id`、`entry_type=session_summary`）。
- 后续对话时，先从向量库检索本会话的 summary 记忆，再参与 Prompt 组装。

### 5) 删除会话联动删除向量数据
- 删除会话历史后，调用 Qdrant 过滤删除接口，按 `connection_id + session_id` 清理对应向量点，避免脏记忆残留。

### 6) token 粗略统计
- 后端返回 `promptTokens/completionTokens/totalTokens`（按字符/4 估算）。
- 前端会话区展示最近一次 `≈Token`，并在历史保存时记录估算值。

### 7) 历史记录结构增强
- `query_history` 增加：
  - `structured_context_json`
  - `token_estimate`
  - `memory_enabled`
- 保存历史时自动附带结构化上下文与 token 估算。

## 启动/构建验证
- 后端 clean 构建：受远程仓库 403 限制，未能在当前环境完成。
- 前端 clean build：通过。

## 备注
- 以上改动均按 UTF-8 文本编码提交。

---

## 追加记录（2026-03-06 11:42）- 编译错误修复

### 修复内容
- 修复后端编译失败：
  - `AiServiceImpl.java` 中多处字符串字面量断行导致 `未结束的字符串文字`，统一改为 `"\n"` 拼接。
  - `QdrantClientServiceImpl.java` 中补齐缺失请求体类型 `DeleteReq`，修复 `DeleteReq` 符号找不到错误。
- 修复前端 TypeScript 编译失败：
  - `App.vue` 中 ER 请求/快照保存移除未在类型中定义的 `memoryEnabled` 字段。
  - 修复 ER 标签页创建与快照恢复流程中的 `null` 类型推断问题，显式收敛为 `ErWorkspaceTab`。
  - 历史会话恢复时补齐 `QueryWorkspaceTab` 必填字段：`memoryEnabled` 与 `lastTokenEstimate`。
- 修复共享类型定义不一致：
  - `packages/shared-contracts/src/index.ts` 中 `AiGenerateChartVO` 补充 `promptTokens/completionTokens/totalTokens` 可选字段。

### 验证结果
- 后端构建：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run` 启动成功，`http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：执行 clean build 后 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6044` 启动成功，`http://127.0.0.1:6044` 返回 HTTP 200。

---

## 追加记录（2026-03-06 13:50）- 长对话按钮 Hover 说明

### 本次目标
- 为查询对话框中的“长对话”开关增加 hover 说明，避免用户不清楚开关含义。

### 关键改动
- 修改文件：`apps/desktop/src/App.vue`
  - 在对话区模型行中，将“长对话”文本与对应开关都包裹 `a-tooltip`。
  - 新增 hover 文案：`开启后会记忆并利用更长的对话上下文，适合连续追问与复杂任务。`
- 修改文件：`apps/desktop/src/style.css`
  - 新增 `.query-chat-long-dialog-label`，替换原内联样式，统一处理左边距与 hover 光标。

### 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建：`npm run -w @sqlcopilot/desktop build` 通过。
- 启动验证（clean）：
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081` 启动成功；`http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
  - 前端：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 后执行 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6046`，`HTTP/1.1 200 OK`。
