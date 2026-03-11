# 主题：query-chat-memory-separation

## 记录

### 2026-03-11 17:35:20

## 本次目标
- 拆分 AI 对话中的“长对话”和“记忆理解”开关，避免两个能力继续共用同一状态。
- 调整解释/分析请求的 SQL 携带规则：仅在 `Auto 模式 + 长对话开启 + SQL 已在会话中` 时允许当前请求不重复携带 SQL，其余情况显式补齐 SQL。

## 关键改动
- 前端查询页签状态拆分为 `conversationMemoryEnabled` 与 `sqlMemoryEnabled`。
- `StudioShell.vue` 中“长对话”仅绑定会话上下文记忆，“记忆理解”仅绑定执行成功 SQL 的持久化记忆。
- `useStudioRuntime.ts` 中 AI 请求统一改为使用 `conversationMemoryEnabled`，SQL 执行请求改为使用 `sqlMemoryEnabled`。
- 非 Auto 的解释/分析动作增加 SQL 兜底：优先取选中 SQL，其次回退最近一条对话 SQL，再回退右侧编辑器 SQL。
- Auto 模式下，当长对话关闭且用户输入表现为解释/分析请求时，自动补入最近 SQL，避免只靠上文无法定位目标 SQL。
- 历史会话恢复逻辑同步适配新的双开关字段。

## 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18096' -Dfile.encoding=UTF-8` 启动成功，`http://127.0.0.1:18096/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端 preview：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4176 --strictPort` 启动成功，`curl -I http://127.0.0.1:4176` 返回 `HTTP/1.1 200 OK`。

## 遗留项
- 当前历史记录表仍保留单一 `memory_enabled` 字段；本轮前端已完成语义拆分，但若后续需要在后端/数据库层面区分“会话记忆”和“SQL 持久记忆”，可再补充独立字段与迁移。


### 2026-03-11 17:45:15

## 本次目标
- 调整 `AiServiceImpl` 中 `if (!hasSqlSnippet)` 的拦截条件，使其与“Auto 模式 + 长对话”场景一致。
- 保留前端“长对话 / 记忆理解”双按钮，但不改前端现有 SQL prompt 拼装逻辑。

## 关键改动
- `AiServiceImpl`：`autoQuery` 命中 `EXPLAIN_SQL` / `ANALYZE_SQL` 且当前 prompt 不含 SQL 时，若会话记忆开启，则从最近对话上下文中提取最近一条 `sqlOutput` 作为后备 SQL；若仍取不到，再维持原来的拦截报错。
- `AiServiceImpl`：新增最近对话 SQL 提取与 prompt 补写辅助方法，仅用于后端 explain/analyze 路由兜底，不影响前端请求格式。
- `StudioShell.vue` / `useStudioRuntime.ts` / `useHistoryModule.ts`：保留“长对话”与“记忆理解”双开关拆分。
- 前端 AI 对话的 SQL 携带逻辑未再改动，仍保持原有行为。

## 验证结果
- 后端打包：`mvn -f apps/server/pom.xml clean package -DskipTests -Dfile.encoding=UTF-8` 通过。
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端构建：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18097' -Dfile.encoding=UTF-8` 启动成功，`http://127.0.0.1:18097/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端 preview：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4178 --strictPort` 启动成功，`curl -I http://127.0.0.1:4178` 返回 `HTTP/1.1 200 OK`。

## 遗留项
- 历史记录表当前仍使用单一 `memory_enabled` 字段承载前端映射值；如果后续需要在后端持久层完全区分“会话记忆”和“SQL 持久记忆”，仍需单独迁移。
