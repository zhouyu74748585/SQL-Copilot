# 主题：object-browser-table-copy

## 记录

### 2026-03-11 18:00:55

## 本次目标
- 为对象浏览页新增表复制能力，支持当前库右键复制与跨库快捷键复制。

## 关键改动
- 后端新增表复制接口与任务查询接口：`POST /api/schema/table/copy`、`GET /api/schema/table/copy/task`。
- 新增 `TableCopyReq`、`TableCopyVO`、`TableCopyTaskVO`、`TableCopyMode`，并实现 `TableCopyServiceImpl`：同库同步复制，跨库/跨连接复制结构+数据走异步任务并返回进度。
- 前端新增表复制模块：`Cmd/Ctrl + C` 记录源表剪贴板，`Cmd/Ctrl + V` 在目标数据库执行粘贴；跨库时弹出确认框，可选择仅复制结构或复制结构和数据。
- 对象右键菜单新增“复制”子菜单，仅支持当前库即时复制；新增复制进度弹窗与样式。

## 验证结果
- 后端打包：`mvn -f apps/server/pom.xml clean package` 通过。
- 前端类型检查：`npm run type-check` 通过（先执行 `npm install` 补齐依赖）。
- 前端构建：`npm run build` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18081"` 成功，`http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173` 成功，`http://127.0.0.1:4173/` 返回 `HTTP/1.1 200 OK`。

## 遗留项
- 当前复制 DDL 以通用元数据重建为主，MySQL/PostgreSQL/SQL Server/SQLite/Oracle 的极端方言细节仍建议后续结合真实库回归验证。
