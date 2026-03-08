# SQL Copilot

## 产品定位

SQL Copilot 是一款基于 Spring Boot + Electron 架构开发的 AI 原生数据库管理工具。具备结构感知能力、执行闭环能力、风险控制能力和上下文记忆能力的智能数据库工作台。

---

## 技术架构

### 技术栈

#### 桌面端
| 技术 | 版本 | 说明 |
|------|------|------|
| Electron | 36.2.1 | 桌面应用框架 |
| Vue | 3.5.13 | 前端框架 |
| Ant Design Vue | 4.2.6 | UI组件库 |
| Vite | 6.0.5 | 构建工具 |
| TypeScript | 5.7.2 | 类型系统 |
| Monaco Editor | 0.55.1 | SQL代码编辑器 |
| ECharts | 5.6.0 | 数据可视化 |

#### 后端
| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.3.4 | 后端框架 |
| GraalVM | 17 | 原生编译目标 |
| MyBatis | 3.0.3 | ORM框架 |
| SQLite | 3.46.0.0 | 本地元数据存储 |
| ONNX Runtime | 1.19.2 | 向量化推理引擎 |
| DJL Tokenizers | 0.29.0 | 分词器 |
| JSQLParser | 4.9 | SQL解析库 |
| JSch | 0.2.20 | SSH隧道支持 |
| Spring AI | 1.0.0-M6 | AI集成框架 |

#### AI与向量服务
| 技术 | 说明 |
|------|------|
| Qdrant | 向量数据库 |
| BGE-M3 | 文本向量化模型 (ONNX格式) |
| OpenAI API | 大语言模型集成 |

---

## 项目结构

```
SQL-Copilot/
├── apps/
│   ├── server/                         # Spring Boot 后端服务
│   │   ├── src/main/java/
│   │   │   └── com/sqlcopilot/studio/
│   │   │       ├── config/            # 配置类 (全局异常处理、Web配置)
│   │   │       ├── controller/        # REST API控制器 (10个)
│   │   │       ├── dto/               # 数据传输对象 (96个)
│   │   │       ├── entity/            # 实体类 (12个)
│   │   │       ├── mapper/            # MyBatis Mapper (10个)
│   │   │       ├── service/           # 业务服务 (11个接口+实现)
│   │   │       │   ├── impl/          # 服务实现 (10个)
│   │   │       │   ├── llm/           # LLM集成 (OpenAI文本客户端)
│   │   │       │   └── rag/           # RAG向量服务 (6个核心服务)
│   │   │       ├── dialect/           # 数据库方言适配
│   │   │       ├── support/           # 工具支持
│   │   │       └── util/              # 工具类
│   │   └── src/main/resources/
│   │       ├── application.yml        # 应用配置
│   │       └── schema.sql             # 数据库初始化脚本
│   │
│   └── desktop/                        # Electron 桌面端
│       ├── electron/                   # Electron主进程 (main.cjs)
│       ├── src/                        # Vue前端源码
│       │   ├── api/                    # API客户端 (client.ts)
│       │   ├── components/             # 公共组件
│       │   │   ├── ErDiagramPanel.vue  # ER图面板
│       │   │   ├── QueryChartPanel.vue # 图表面板
│       │   │   └── TableEditor.vue     # 表结构编辑器
│       │   ├── modules/studio/         # 主工作区模块
│       │   │   ├── components/
│       │   │   │   └── StudioShell.vue  # 核心工作区外壳 (2785行)
│       │   │   └── composables/         # 组合式API (10个)
│       │   └── types/                  # TypeScript类型
│       └── resources/                  # 静态资源
│
├── packages/
│   └── shared-contracts/               # 前后端共享契约
│
├── model/                              # AI模型文件
│   ├── *.onnx                          # 向量化模型
│   └── *.model                        # 模型配置
│
└── docs/                              # 开发文档
```

---

## 核心功能模块

### 1. 数据库连接与管理
- **多数据库支持**: MySQL、PostgreSQL、SQL Server、SQLite、Oracle 等
- **连接创建/编辑/删除**: 完整的连接生命周期管理
- **连接测试**: 验证连接有效性
- **SSH隧道支持**: 安全远程连接
- **数据库预览**: 临时连接查看数据库列表

### 2. Schema 结构管理
- **自动同步**: 从数据库读取并同步 schema 信息
- **表结构查看**: 字段、索引、外键关系详情
- **表统计信息**: 行数估算、大表排名
- **Schema缓存**: 定时刷新机制 (默认5分钟TTL)
- **DDL操作**: 支持 CREATE/ALTER/DROP/TRUNCATE 表
- **ER图生成**: 基于外键和AI推断的关系图

### 3. AI 智能查询 (核心)
| 接口 | 功能 |
|------|------|
| `/api/ai/query/generate` | 自然语言生成SQL |
| `/api/ai/query/auto` | 自动意图识别+路由 |
| `/api/ai/query/explain` | SQL语句解释 |
| `/api/ai/query/analyze` | SQL合理性分析 |
| `/api/ai/query/repair` | SQL语法错误修复 |
| `/api/ai/query/generate-chart` | 生成图表配置 |

**AI 工作流**:
```
用户输入 → 轻量意图预判 → RAG检索 → 最终意图识别 → LLM执行 → 结果返回
```

### 4. SQL 执行与分析
- **SQL执行**: 支持任意SQL语句执行
- **EXPLAIN分析**: 执行计划分析
- **风险评估**: 高风险操作预警
- **结果导出**: CSV/Excel/JSON 格式导出

### 5. RAG 向量化服务
| 接口 | 功能 |
|------|------|
| `/api/rag/vectorize/enqueue` | 加入向量化队列 |
| `/api/rag/vectorize/table/manual` | 手动向量化单张表 |
| `/api/rag/vectorize/status/list` | 向量化状态列表 |
| `/api/rag/vectorize/overview` | 向量化概览 |
| `/api/knowledge/vectorize/rebuild` | 重建向量索引 |

**向量化分层策略**:
- `schema_table`: 每张表一个向量
- `schema_column`: 每个字段一个向量
- `sql_history`: 每条SQL历史一个向量
- `sql_fragment`: SQL片段级向量 (CTE/SELECT片段)

### 6. 知识库管理
- **术语管理**: 业务术语的增删改查
- **SQL示例**: 典型SQL模式的保存与管理
- **向量检索**: 基于语义的相似查询召回

### 7. 编辑器与历史
- **多标签页**: 支持 AI查询、ER图、表编辑器等多种Tab
- **查询历史**: 会话历史记录、分页查看
- **历史会话**: 续接历史会话继续对话
- **ER图快照**: 保存/重命名/删除ER图
- **保存查询**: 固定查询模板保存
- **图表缓存**: 查询结果的图表缓存

---

## API 接口总览

### 健康检查
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health/health` | 服务健康状态 |

### 连接管理 (`/api/connection`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/list` | 获取连接列表 |
| POST | `/create` | 创建连接 |
| POST | `/update` | 更新连接 |
| POST | `/remove` | 删除连接 |
| POST | `/test` | 测试连接 |
| POST | `/databases/preview` | 预览数据库列表 |

### Schema 管理 (`/api/schema`)
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/sync` | 同步Schema |
| GET | `/overview` | Schema概览 |
| GET | `/tableStats` | 表统计信息 |
| GET | `/tableDetail` | 表详情 |
| GET | `/databases` | 数据库列表 |
| GET | `/objectNames` | 对象名称列表 |
| POST | `/context/build` | generation_context |
| POST | `/er/graph` | 生成ER图 |
| POST | `/table/create` | 创建表 |
| POST | `/table/alter` | 修改表 |
| POST | `/table/drop` | 删除表 |
| POST | `/table/truncate` | 清空表 |
| POST | `/cache/refresh` | 刷新缓存 |

### SQL 执行 (`/api/sql`)
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/execute` | 执行SQL |
| POST | `/explain` | EXPLAIN分析 |
| POST | `/risk/evaluate` | 风险评估 |

### AI 查询 (`/api/ai/query`)
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/generate` | 生成SQL |
| POST | `/auto` | 自动模式 |
| POST | `/generate-chart` | 生成图表 |
| POST | `/explain` | 解释SQL |
| POST | `/analyze` | 分析SQL |
| POST | `/repair` | 修复SQL |

### AI 配置 (`/api/ai/config`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/get` | 获取配置 |
| POST | `/save` | 保存配置 |

### 编辑器 (`/api/editor`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/history/list` | 历史列表 |
| GET | `/history/session/page` | 会话分页 |
| GET | `/history/session/detail` | 会话详情 |
| POST | `/history/save` | 保存历史 |
| POST | `/history/session/remove` | 删除会话 |
| GET | `/saved-query/list` | 保存查询列表 |
| POST | `/saved-query/save` | 保存查询 |
| GET | `/er/snapshot/page` | ER图快照 |
| GET | `/er/snapshot/detail` | 快照详情 |
| POST | `/er/snapshot/save` | 保存快照 |
| POST | `/er/snapshot/rename` | 重命名快照 |
| POST | `/er/snapshot/remove` | 删除快照 |
| POST | `/result/export` | 导出结果 |

### 知识库 (`/api/knowledge`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/term/list` | 术语列表 |
| POST | `/term/save` | 保存术语 |
| POST | `/term/remove` | 删除术语 |
| GET | `/example/list` | SQL示例列表 |
| POST | `/example/save` | 保存示例 |
| POST | `/example/remove` | 删除示例 |
| POST | `/vectorize/rebuild` | 重建向量 |

### RAG 配置 (`/api/rag/config`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/get` | 获取配置 |
| POST | `/save` | 保存配置 |

### RAG 向量 (`/api/rag/vectorize`)
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/enqueue` | 加入队列 |
| POST | `/interrupt` | 中断任务 |
| POST | `/table/manual` | 手动向量化 |
| GET | `/status/list` | 状态列表 |
| GET | `/overview` | 概览 |
| GET | `/runtime-provider` | 运行时提供商 |

---

## 前端界面结构

### 工作区标签页类型
1. **对象浏览**: 数据库对象树形结构
2. **AI查询**: 智能问答式查询界面
3. **ER图**: 关系图可视化
4. **表编辑器**: 表结构编辑界面
5. **知识库**: 术语和SQL示例管理

### 顶部工具栏
- 新建 AI 查询页签
- 会话历史 (下拉菜单)
- ER图快照 (下拉菜单)

### 组件说明
| 组件 | 路径 | 功能 |
|------|------|------|
| StudioShell | modules/studio/components/ | 核心工作区外壳 |
| ErDiagramPanel | components/ | ER图可视化 (ECharts) |
| QueryChartPanel | components/ | 图表展示 |
| TableEditor | components/ | 表结构编辑 |

---

## 关键技术实现

### RAG 向量架构
后端采用 ONNX Runtime 作为推理引擎，支持多种执行 provider:
- **CPU**: 默认通用执行
- **CUDA**: NVIDIA GPU 加速
- **CoreML**: APPLE 推理框架 加速

向量化模型: BGE-M3 (多语言embedding模型)

### AI 意图识别
双层意图识别架构:
1. **轻量预判** (`INTENT_CLASSIFY_LIGHT_SYSTEM_PROMPT`): 快速初筛
2. **最终识别** (`INTENT_CLASSIFY_FINAL_SYSTEM_PROMPT`): 结合RAG结果精准判断

支持四种意图: `GENERATE_SQL`, `EXPLAIN_SQL`, `ANALYZE_SQL`, `GENERATE_CHART`

### SQL 执行闭环
```
LLM生成 → JSQLParser解析 → 风险评估 → 执行 → 结果转换 → 异常修复(如需)
```

### ER 图智能推断
- 外键关系: 实线连接
- AI推断关系: 虚线连接 (置信度阈值 0.6)
- 渲染引擎: Html+SVG

---

## 开发环境搭建

### 环境要求
| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | 后端运行环境 |
| Node.js | 18+ | 前端构建 |
| Maven | 3.8+ | 后端构建 |
| Qdrant | - | 向量数据库 |

### 启动命令

```bash
# 1. 安装前端依赖
npm install

# 2. 下载 Qdrant (可选，本地AI功能必需)
npm run -w @sqlcopilot/desktop download:qdrant

# 3. 启动后端
cd apps/server
mvn spring-boot:run

# 4. 启动前端开发
npm run -w @sqlcopilot/desktop dev

# 5. 启动 Electron 调试
npm run -w @sqlcopilot/desktop debug
```

**服务端口**:
- 后端: `http://localhost:18080`
- 前端渲染: `http://127.0.0.1:8888`
- Electron主进程调试: `--inspect=9229`

---

## 构建与打包

```bash
# 后端编译
cd apps/server && mvn clean package

# 前端类型检查
npm run type-check

# 前端构建
npm run build

# Electron 打包 (含 Qdrant 资源)
npm run -w @sqlcopilot/desktop dist
```

**打包目标**:
- Windows: NSIS
- macOS: DMG
- Linux: AppImage

---

## 配置说明

### application.yml 核心配置

```yaml
server:
  port: 18080

spring:
  datasource:
    driver-class-name: org.sqlite.JDBC
    url: jdbc:sqlite:sql-copilot.db

rag:
  enabled: true
  qdrant:
    url: http://127.0.0.1:6333
  embedding:
    model-file-name: model_optimized.onnx
    execution-provider: AUTO  # CPU/CUDA/DirectML
```

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `SQLCOPILOT_DJL_CACHE_DIR` | DJL模型缓存 | `~/.sql-copilot/djl-cache` |
| `OPENAI_API_KEY` | OpenAI密钥 | - |

---

## 常见问题

**Q: 启动后端报错 DJL 缓存无法写入?**
A: 系统会自动尝试 `%LOCALAPPDATA%\SQL-Copilot\djl-cache` → `~/.sql-copilot\djl-cache` → 临时目录

**Q: RAG 功能需要 Qdrant 吗?**
A: 是的，需启动 Qdrant 服务。或在配置中设置 `rag.enabled: false` 禁用

**Q: 支持本地向量化模型吗?**
A: 支持，`model/` 目录下已包含 BGE-M3 的 ONNX 模型，`rag.embedding.execution-provider` 可选 CPU/CUDA/DirectML
