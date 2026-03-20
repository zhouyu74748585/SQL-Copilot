# 主题：readme-architecture-docs

## 记录

### 2026-03-11 16:06:01

## 本次目标
- 根据当前实际代码更新 README，补齐技术栈、功能点、特点与开发/打包命令。
- 在 docs 中新增一份基于真实实现的技术架构文档，覆盖模块划分、关键技术点以及 AI 对话/上下文压缩等关键流程图。

## 关键改动
- 重写 `README.md`：
  - 依据 `package.json`、`apps/desktop/package.json`、`apps/server/pom.xml`、`application*.yml`、`scripts/package-variants.mjs` 重新整理技术栈与命令。
  - 依据前端工作台模块、后端控制器与 AI/RAG 实现，重新整理当前已实现功能与项目特点。
  - 补充 `minimal / medium / full` 三种变体的实际差异。
- 新增 `docs/20260311160122-technical-architecture.md`：
  - 说明 Electron、Vue 渲染层、Spring Boot、本地 SQLite、Qdrant、ONNX 模型与打包脚本的整体结构。
  - 按实际代码拆分前端 composable 模块和后端 controller/service/rag/context 模块。
  - 新增 Mermaid 图：总体架构图、AI 对话处理时序图、会话上下文压缩流程图、工作台交互流程图、多变体打包流程图。

## 验证结果
- 前端 clean 构建通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 前端预览通过：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 18091 --strictPort`
  - `curl --noproxy '*' -I http://127.0.0.1:18091/` 返回 `HTTP/1.1 200 OK`
- 后端 clean 启动通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18084"`
  - `curl --noproxy '*' http://127.0.0.1:18084/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`

## 说明
- 本轮仅修改文档，无业务代码与接口行为变更。
- 保持 UTF-8 编码。


### 2026-03-11 16:18:10

## 追加记录（2026-03-11）- README 文案强化与截图展示

### 本次目标
- 调整 README 的叙述方式，不再以“技术栈清单”开头，而是更强调产品能力、使用价值和功能特点。
- 将 `docs/img` 中现有应用截图插入 README，用更直观的方式展示当前已实现界面。

### 关键改动
- 重写 `README.md` 的内容顺序：
  - 先说明 SQL Copilot 不是单纯的 SQL 生成器，而是包含连接管理、Schema 感知、AI 对话、风险控制、执行闭环、ER 图和知识记忆的数据库工作台。
  - 新增“为什么它更像数据库副驾”“核心功能特点”等更偏产品表达的章节。
  - 将原本偏静态罗列的功能点改为更强调价值与使用场景的描述。
- 在 README 中插入现有截图：
  - `docs/img/对话查询.png`
  - `docs/img/对象浏览.png`
  - `docs/img/数据浏览.png`
  - `docs/img/智能ER图.png`
- 保留并下移技术栈、目录结构、开发命令、打包命令和变体说明，使 README 同时兼顾吸引力与工程可用性。

### 验证结果
- 前端 clean 构建通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 前端预览通过：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 18092 --strictPort`
  - `curl --noproxy '*' -I http://127.0.0.1:18092/` 返回 `HTTP/1.1 200 OK`
- 后端 clean 启动通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18085"`
  - `curl --noproxy '*' http://127.0.0.1:18085/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`

### 说明
- 本轮仅调整 README 表达和截图展示方式，未修改业务代码。
- 保持 UTF-8 编码。


### 2026-03-11 16:23:22

## 追加记录（2026-03-11）- README 截图路径修复

### 本次目标
- 修复 README 中截图无法展示的问题。

### 关键改动
- 保留 `docs/img` 中原有中文文件名截图。
- 新增一组 ASCII 文件名副本，避免部分 Markdown 渲染环境对中文路径处理不稳定：
  - `docs/img/query-workbench.png`
  - `docs/img/object-browser.png`
  - `docs/img/data-browser.png`
  - `docs/img/er-diagram.png`
- 将 `README.md` 中的图片引用改为上述 ASCII 文件名。

### 验证结果
- 前端 clean 构建通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 前端预览通过：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 18093 --strictPort`
  - `curl --noproxy '*' -I http://127.0.0.1:18093/` 返回 `HTTP/1.1 200 OK`
- 后端 clean 启动通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18086"`
  - `curl --noproxy '*' http://127.0.0.1:18086/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`

### 说明
- 本轮未调整业务代码，仅修复文档资源引用路径。

### 2026-03-20 08:51:47

## 追加记录（2026-03-20）- README 模块画廊与最新截图更新

### 本次目标
- 将 README 的“应用截图”从 4 张核心图扩展为覆盖主要模块的模块画廊。
- 用本轮真实运行态采集的截图替换旧图，并补齐表结构编辑、对象定义编辑、历史菜单、知识中心、Redis 浏览和设置界面。

### 关键改动
- 更新 `README.md` 的“应用截图”章节：
  - 保留原有对话式查询、对象浏览、表数据、ER 图的展示方向，但全部换成本轮最新截图。
  - 新增 `table-editor.png`、`object-definition-editor.png`、`history-snapshots.png`、`knowledge-center.png`、`redis-browser.png`、`settings-appearance.png` 六张模块图。
  - 将截图说明改为模块画廊式组织，按页面用途描述，而不是只保留 4 个大类页面。
- 更新 `docs/img` 目录截图资产：
  - 覆盖 `query-workbench.png`
  - 覆盖 `object-browser.png`
  - 覆盖 `data-browser.png`
  - 覆盖 `er-diagram.png`
  - 新增 `table-editor.png`
  - 新增 `object-definition-editor.png`
  - 新增 `history-snapshots.png`
  - 新增 `knowledge-center.png`
  - 新增 `redis-browser.png`
  - 新增 `settings-appearance.png`

### 截图来源
- 所有截图均来自桌面端真实运行态。
- 统一使用中文浅色主题。
- 演示数据基于本轮补充的本地 SQLite 样例库与本地元数据库记录。

### 验证结果
- 前端 clean 构建：`npm run build` 通过。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6068 --strictPort` 启动成功。
- `http://127.0.0.1:6068/` 返回 `HTTP 200`。
- 后端健康检查：`http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。

### 说明
- 本轮 README 更新同时依赖前端/后端运行态联调，因此除文档和图片外，还包含为截图准备的演示数据与一处 JDBC 驱动类加载修复。
