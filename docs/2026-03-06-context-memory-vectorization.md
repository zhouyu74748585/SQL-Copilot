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
