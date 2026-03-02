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
