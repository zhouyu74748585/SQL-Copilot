# SQL Copilot

SQL Copilot 不是一个只会“吐一段 SQL”的问答工具，而是一个已经打通了连接管理、Schema 感知、AI 对话、风险控制、结果执行、图表展示、ER 分析和知识记忆的桌面化数据库工作台。

它当前的实际实现形态是：

- Electron 桌面端，提供多标签工作台、Monaco SQL 编辑器、ER 图和数据浏览界面
- Spring Boot 本地服务，承接连接管理、Schema 缓存、SQL 执行、AI 对话与 RAG 检索
- SQLite 本地元数据存储，保存连接、历史、快照、知识库和配置
- Qdrant + ONNX / OpenAI Compatible，支撑向量检索、知识召回和会话记忆

## 为什么它更像“数据库副驾”

- 不只是生成 SQL，而是从自然语言到 SQL、解释、分析、图表、修复的一整条 AI 工作流。
- 不只是会话框，而是带连接树、对象浏览、表结构编辑、表数据编辑、ER 图、历史会话和知识库的完整工作台。
- 不只是检索当前问题，而是会结合 Schema、样例 SQL、术语、历史 SQL 和会话长期记忆去构建上下文。
- 不只是返回文本，而是支持风险评估、执行结果表格、图表缓存、CSV 导出和历史恢复。

## 应用截图

### 对话式查询工作台

![对话查询](docs/img/query-workbench.png)

- 在同一页面中完成自然语言提问、SQL 生成、流式输出、结果追问和执行闭环。
- 支持 `auto` 自动模式，先做意图识别，再路由到生成 SQL、解释 SQL、分析 SQL 或生成图表。

### 对象浏览与连接工作区

![对象浏览](docs/img/object-browser.png)

- 左侧连接树和对象树联动，支持数据库、表、视图等对象浏览。
- 可直接从对象右键进入查询、表数据浏览、表结构编辑和向量化动作。

### 表数据浏览与编辑

![数据浏览](docs/img/data-browser.png)

- 支持分页、筛选、排序、单元格编辑、插入、删除与事务提交。
- 更适合做“定位数据 + 快速修正 + 再回到 AI 查询”的联合作业。

### 智能 ER 图

![智能ER图](docs/img/er-diagram.png)

- 不只展示外键关系，还支持 AI 推断关系。
- 可保存快照、再次打开、调整布局，并继续围绕当前结构进行分析。

## 核心功能特点

### 1. 面向数据库工作的 AI 对话

- 对话式 SQL 生成、SQL 解释、SQL 分析、SQL 修复、图表方案生成都已落地。
- SSE 流式输出支持 `thinking` 与最终结果分段展示，便于观察 AI 处理过程。
- 查询结果可直接回填到 SQL 编辑器，再进入执行、Explain 或风险评估。

### 2. 真正带上下文的 SQL Copilot

- 当前实现会组合 Schema 表、字段、术语、样例 SQL、历史 SQL、多轮会话窗口与长期记忆。
- 会话上下文不是简单拼接原文，而是支持窗口摘要、滑动摘要、结构化窗口 JSON 与长期向量记忆召回。
- 长期记忆写入 `session_summary` 向量记忆，默认保留 30 天。

### 3. 查询不是终点，执行闭环才是重点

- 生成 SQL 后可直接执行。
- 执行前会先做风险评估，拦截高风险操作和只读连接违规写入。
- 结果可以表格化查看、转图表、缓存图表图片、导出 CSV。

### 4. 不只有 AI，会把数据库工作台补全

- 连接管理：创建、编辑、删除、测试、数据库列表预览。
- 对象浏览：数据库/表/视图等对象树、表概览、表统计、对象详情。
- 表设计：支持新建表、修改表、DDL 预览与执行。
- 数据编辑：支持表数据页的筛选、排序、单元格编辑和提交。
- ER 图：支持外键关系 + AI 推断关系、快照保存与恢复。
- 知识库：术语管理、样例 SQL 管理、知识向量重建。
- 历史中心：会话分页查看、恢复打开、删除、标题重命名。

### 5. 本地桌面交付，不依赖浏览器页面拼装

- Electron 主进程负责窗口、图表缓存、本地资源与打包态子进程托管。
- 打包时会把 backend 和 Qdrant 资源一起准备好，桌面端不是“只有一个前端壳”。
- `scripts/package-variants.mjs` 会为 backend 执行 Maven 打包，再通过 `jdeps + jlink` 生成裁剪运行时。

## 当前已实现模块

| 模块 | 当前实际能力 |
| --- | --- |
| 连接与对象浏览 | 连接管理、数据库列表、对象树、表统计、对象详情、右键快捷动作 |
| AI 查询 | 生成 SQL、自动模式、解释 SQL、分析 SQL、生成图表、修复 SQL、SSE 流式输出 |
| SQL 执行 | 执行、Explain、风险评估、结果展示、CSV 导出 |
| Schema / 表设计 | Schema 同步、表详情、对象名补全、建表/改表/删表/清表 |
| 表数据 | 分页、筛选、排序、编辑、提交 |
| ER 图 | 外键关系、AI 推断关系、快照保存/改名/删除/重开 |
| 历史 | 会话分页、历史恢复、删除、标题覆写 |
| 知识库 | 术语、样例 SQL、从查询保存样例、重建向量 |
| RAG | 表/字段/历史/术语/样例 SQL 多桶检索、rerank、跨作用域召回 |

## 技术栈

### 桌面端

| 技术 | 实际版本/实现 | 说明 |
| --- | --- | --- |
| Electron | 36.2.1 | 桌面容器与本地资源调度 |
| Vue 3 | 3.5.13 | 渲染层框架 |
| TypeScript | 5.7.2 | 前端类型系统 |
| Ant Design Vue | 4.2.6 | UI 组件库 |
| Vite | 6.0.5 | 前端构建与预览 |
| Monaco Editor | 0.55.1 | SQL 编辑与补全 |
| ECharts | 5.6.0 | 图表与 ER 图渲染 |

### 后端

| 技术 | 实际版本/实现 | 说明 |
| --- | --- | --- |
| Spring Boot | 3.3.4 | 本地 HTTP 服务 |
| Java | 17 | 后端运行与打包基线 |
| MyBatis Spring Boot | 3.0.5 | SQLite 持久化访问 |
| SQLite JDBC | 3.46.0.0 | 本地元数据库 |
| Spring AI Core | 1.0.0-M6 | AI 接入基础依赖 |
| JSQLParser | 4.9 | SQL AST 校验与表名提取 |
| JSch | 0.2.20 | SSH 隧道支持 |

### AI / RAG / 本地推理

| 技术 | 实际版本/实现 | 说明 |
| --- | --- | --- |
| Qdrant | Electron 资源内置二进制 | 向量数据库 |
| ONNX Runtime | 1.19.2 | 本地 embedding / rerank 推理 |
| DJL Tokenizers | 0.29.0 | 本地 tokenizer |
| OpenAI Compatible API | 已接入 | 在线模型、在线 embedding / rerank |
| BGE-M3 / BGE-Reranker | 外部下载 + 本地目录配置 | 保留 ONNX 框架能力，但打包产物默认不携带模型文件 |

## 项目结构

```text
.
├── apps/desktop                    Electron + Vue 桌面端
│   ├── electron                    主进程与 preload
│   ├── src/modules/studio          主工作台与功能模块
│   └── resources                   Qdrant 与打包态 backend 资源
├── apps/server                     Spring Boot 本地服务
│   ├── controller                  HTTP 接口
│   ├── service                     业务服务
│   ├── service/llm                 LLM 网关
│   ├── service/rag                 检索、向量化、rerank
│   ├── mapper/entity               SQLite 持久化
│   └── resources                   application.yml / schema.sql / drivers
├── packages/shared-contracts       前后端共享契约
├── scripts/package-variants.mjs    单包打包脚本
└── docs                            文档、截图与阶段总结
```

## 开发命令

### 前端开发

```bash
npm run -w @sqlcopilot/desktop dev:renderer
npm run -w @sqlcopilot/desktop dev:electron
npm run -w @sqlcopilot/desktop debug
```

### 前端校验与预览

```bash
npm run type-check
npm run build
npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort
```

### 后端开发与校验

```bash
mvn -f apps/server/pom.xml clean spring-boot:run
mvn -f apps/server/pom.xml clean package
```

### 桌面端打包

```bash
npm run -w @sqlcopilot/desktop dist
```

### 一键打包

```bash
npm run package:app
```

## 打包说明

当前仅保留一种桌面打包形态，统一输出到 `release/desktop`。

- 打包命令统一使用 `npm run package:app`。
- 打包流程会自动为 backend 执行 Maven 构建，并通过 `jdeps + jlink` 生成随桌面端一起分发的运行时。
- 前端仍保留本地 ONNX / 在线两种 RAG 运行方式，但安装包默认不内置模型文件，需要用户自行下载并配置本地模型目录。

## 模型下载

- Embedding 模型：
  - https://huggingface.co/hooman650/bge-m3-onnx-o4/tree/main
- Rerank 模型：
  - https://huggingface.co/swulling/bge-reranker-base-onnx-o4/tree/main
- 在桌面应用设置中点击下载链接时，会直接调用系统默认浏览器打开，不会在应用内弹窗下载。

## 启动检查

- 后端健康检查：`http://127.0.0.1:18080/api/health`
- 前端 API 默认直连：`http://localhost:18080`
- 本地元数据默认落在工程根目录：`sql-copilot.db`
- 更详细的代码级说明见 `docs/20260311160122-technical-architecture.md`
