# SQL Copilot MVP

## 目录结构

- `apps/server`: Spring Boot 本地后端服务（端口 `18080`）
- `apps/desktop`: Vue3 + Ant Design Vue 桌面端前端壳
- `packages/shared-contracts`: 前后端共享契约类型

## 本地运行

### 1. 启动后端

```bash
cd apps/server
mvn spring-boot:run
```

### 2. 启动前端

```bash
npm install
npm run -w @sqlcopilot/desktop dev
```

### 3. 启动 Electron 调试

```bash
npm run -w @sqlcopilot/desktop debug
```

### 4. 下载 Qdrant 二进制（当前平台）

```bash
npm run -w @sqlcopilot/desktop download:qdrant
```

### 5. 打包 Electron 安装物（包含 Qdrant 资源）

```bash
npm run -w @sqlcopilot/desktop dist
```

说明：
- 渲染进程：Vite 在 `http://127.0.0.1:5173`
- 主进程：Electron 以 `--inspect=9229` 启动，可用 IDE/Chrome DevTools 附加调试
- 会自动打开 Electron DevTools（独立窗口）

## 构建校验

```bash
cd apps/server && mvn clean package
npm run type-check
npm run build
```

## RAG 与向量化

- 后端在读取到数据库元数据后会自动进行分层向量化写入：
- `schema_table`：每张表一个向量
- `schema_column`：每个字段一个向量
- `sql_history`：每条 SQL 一个向量（附带 `tables/columns/join_count/has_cte`）
- `sql_fragment`：可选 SQL 片段级向量（CTE / SELECT 片段）

- 向量化默认使用 ONNX Runtime + BGE-M3，配置位于：
- `apps/server/src/main/resources/application.yml` 的 `rag.*`
