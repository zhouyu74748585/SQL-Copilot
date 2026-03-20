---
title: 功能验收闭环
author: codex
date: 2026-03-20
encoding: utf-8
---

## 验收基线说明
- 基于 `README.md` 中列出的「当前已实现模块」，对比 PRD 仅用以补充背景，未落地 PRD 功能不算失败。  
- 所有轮次建立在 clean 启动标准：`mvn -f apps/server/pom.xml clean package`、`npm run -w @sqlcopilot/desktop build`、`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort`、Electron 连接 `18080` 后端并可访问。
- 验收数据源以 `Local SQLite Demo` 为主；`Redis`/`MySQL`/`SQL Server` 作为 P1 烟测；AI 提升链路要求可跑 `generate/auto/explain/analyze/repair`。
- 自动化产物固定输出到 `output/playwright/round-{1|2|3}/`，含 `acceptance-results.json`、`summary.md`、日志和截图。

## 本轮新增资产
- 新增根脚本：`scripts/acceptance/run-acceptance.cjs`
- 新增 Electron + Playwright 验收脚本：`scripts/acceptance/run-electron-acceptance.cjs`
- 新增公共工具：`scripts/acceptance/lib/common.cjs`
- 新增命令：`npm run qa:acceptance`
- 忽略验收产物目录：`.gitignore` 增加 `/output/`

## 功能验收清单
### P0 启动基线
- 数据源：所有硬件统一依赖本地 Meta DB（`sql-copilot.db`）+ `Local SQLite Demo` 承载演示数据。
- 步骤：后端 clean package -> 启动 `18080` -> 前端 clean build -> `vite preview` -> Electron headless 访问。
- 预期：后端 `/api/health` 返回成功，前端预览可达，Electron 成功加载工作台。
- 失败判定：任一步骤报错、窗口无法显示 UI、后端 `/api/health` 失败。
- 可自动回归：是（可借助现有 `scripts` + Playwright 复用）。

### P0 SQLite Demo 全功能验收
- 数据源：连接 ID 5 的 `Local SQLite Demo`，包含 `customers/orders/order_items/v_order_summary`。
- 步骤：确认连接/对象浏览；打开查询+执行 `v_order_summary`; 保存查询并再次打开；AI 模式皆可触发后端 `generate/auto/explain/analyze/repair`; 表数据页完成筛选/排序/分页/单元格编辑回滚；表结构+对象定义编辑可展示 SQL；ER 图生成/保存/恢复；历史/知识/国际化切换。
- 预期：操作顺畅、响应数据准确、AI 请求返回可执行 SQL、界面无中文残留。
- 失败判定：任意模块无法加载、AI Endpoint 抛错、表数据提交失败未恢复、ER 图保存失败等。
- 可自动回归：部分环节可由 Playwright 脚本回放（查询、ER、AI、表数据、国际化），但 AI 需验证网络。

### P1 Redis 烟测
- 数据源：连接 ID 4 `本地redis`。
- 步骤：选择 Redis 浏览，展开层级树，右侧显示 TTL/详情，执行 `qa:acceptance:*` 键的新增/编辑/删除并清理。
- 预期：树表展开正常，操作后无残留键，接口 `/api/kv/redis/key/*` 成功。
- 失败判定：树表不展开、接口返错、键残留。
- 可自动回归：可部分复用 Playwright 场景，键操作需配合脚本。

### P1 MySQL / SQL Server 烟测
- 数据源：连接 ID 3 (`MYSQL`), 2 (`SQLSERVER`)。
- 步骤：右键打开连接列表，测试连接、拉取数据库，进入任意库对象浏览，打开 SQL 查询模板。
- 预期：MySQL 默认 `LIMIT 100`，SQL Server 默认 `TOP 100`，连接信息展示无误，界面无异常。
- 失败判定：连接树无法展开、SQL 模板方言错位、连接测试返回 `BLOCKED` 但 UI 未提示。
- 可自动回归：自动化脚本可模拟连接/对象浏览，但依赖真实数据库在线。

## 验收轮次结果
### Round 1
- 结果概览：`PASS 15 / FAIL 1 / BLOCKED 2`
- 关键通过项：
  - 启动基线、SQLite 对象浏览、查询保存、Explain / Analyze / Repair、表数据编辑回滚、表结构、对象定义、ER 快照、历史/知识、国际化、Redis 烟测通过。
  - 证据目录：`output/playwright/round-1/`
- 未通过项：
  - `sqlite-ai-generate-execute`
    - 现象：AI 生成 SQL 执行失败，先后命中过 `c.customer_id` 不存在、`order_summary_view` 不存在等问题。
    - 影响：P0 生成 SQL 闭环仍不稳定。
  - `mysql-smoke`
    - 调整后降为 `BLOCKED`
  - `sqlserver-smoke`
    - `BLOCKED`
- 本轮修复动作：
  - 修复验收脚本在 preview 构建态下无法访问控制器的问题，改为“后端真实接口验收 + Playwright UI 见证”。
  - 修复 Windows 下 `mvn.cmd` / `npm.cmd` / `JAVA_HOME` 的启动兼容问题。
  - 修复表数据页、历史、Redis、i18n 等自动化脚本误判。

### Round 2
- 结果概览：`PASS 15 / FAIL 1 / BLOCKED 2`
- 成果：
  - Round 1 的脚本噪声已清空，剩余失败集中到 `sqlite-ai-generate-execute`。
  - `MySQL` 与 `SQL Server` 已确认连接与对象浏览烟测通过。
- 修复清单：
  - 调整 AI 生成验收样例，使其更贴近 demo schema 语义。
  - 改进 SQL 提取逻辑，清理 `SQL:` 前缀和代码块包装。
- 仍未通过：
  - `sqlite-ai-generate-execute`
    - 现象：生成结果出现 `near "SQL": syntax error`
    - 判断：主要是模型输出格式噪声与生成稳定性问题。

### Round 3
- 结果概览：`PASS 14 / FAIL 2 / BLOCKED 2`
- 成果：
  - 启动、SQLite 主工作流、Redis 烟测继续保持通过。
  - 生成 SQL 提取逻辑进一步收紧，能剥离 `SQL:` 前缀、代码块和前置说明。
  - 证据目录：`output/playwright/round-3/`
- 未通过项：
  - `sqlite-ai-generate-execute`
    - 现象：AI 生成 SQL 仍引用不存在对象 `order_summary_view`
    - 影响：自然语言 -> SQL -> 执行闭环仍不稳定
    - 证据：`output/playwright/round-3/screenshots/sqlite-ai-generate-execute-failed.png`
  - `sqlite-ai-repair`
    - 现象：Repair 返回 SQL 执行时报 `no such column: order_id`
    - 影响：失败 SQL 自动修复链路在 demo schema 上仍不稳定
    - 证据：`output/playwright/round-3/screenshots/sqlite-ai-repair-failed.png`
  - `mysql-smoke`
    - `BLOCKED`：连接与对象浏览通过，但默认 SQL 模板的 Electron UI 路径未补完自动化校验
  - `sqlserver-smoke`
    - `BLOCKED`：连接与对象浏览通过，但默认 SQL 模板的 Electron UI 路径未补完自动化校验

## AI 生成问题分析
- 子代理只读分析结论：
  - 生成链路主要在 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/AiServiceImpl.java`
  - 上下文组装在 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/AiConversationContextManager.java`
  - Schema fallback 在 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/SchemaServiceImpl.java`
  - RAG prompt 组装在 `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagRetrievalServiceImpl.java`
- 当前最可能的低风险修复方向：
  - 在 prompt / schema context 中显式补充 FK / join 锚点，例如：
    - `orders.customer_id -> customers.id`
    - `order_items.order_id -> orders.id`
  - 在 `validateByAst(...)` 之后增加轻量列校验，至少覆盖 `SELECT / WHERE / JOIN ON`
  - 收紧生成 prompt，禁止猜测对称 `*_id` 列名与不存在的视图名

## 最终结论
- 当前状态已缓存，任务暂停。
- 结论：
  - 自动化验收基建已落地，可重复执行 clean 启动 + preview + Electron + Playwright 验收。
  - 截至 Round 3，主体模块大多通过，剩余 2 个 P0 AI 闭环失败、2 个 P1 自动化覆盖阻塞。
- 明日续做建议：
  1. 优先修 `AiServiceImpl` / prompt context 的关系锚点与列级校验。
  2. 修复后复跑 `node scripts/acceptance/run-acceptance.cjs --round=3`。
  3. 补 MySQL / SQL Server 默认 SQL 模板的真实 UI 自动化路径。
