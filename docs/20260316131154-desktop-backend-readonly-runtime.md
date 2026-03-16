# 主题：desktop-backend-readonly-runtime

## 记录

### 2026-03-16 13:11:54

## 本次目标
- 修复桌面端内置后端在 DMG 挂载目录启动时因只读文件系统导致的 `chmod run.sh` 失败问题。

## 关键改动
- 修改 `apps/desktop/electron/main.cjs` 的 `ensureExecutable`：
  - 先用 `fs.accessSync(..., X_OK)` 检查目标是否已可执行。
  - 仅在确实不可执行时才尝试 `chmod 755`。
  - 当遇到 `EROFS`、`EPERM`、`EACCES` 时，如果文件本身已具备执行权限则直接继续，不再中断启动。
- 调整后端启动逻辑：
  - 启动 `run.sh` 时移除预先 `chmod`，改为直接通过 `/bin/bash run.sh` 执行，避免对只读资源卷做无意义写操作。

## 验证结果
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动通过：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18087"`
- 后端健康检查通过：`GET http://127.0.0.1:18087/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 18094 --strictPort`
- 前端可达性检查通过：`curl --noproxy '*' -I http://127.0.0.1:18094/` 返回 `HTTP/1.1 200 OK`

## 说明
- 本次未重新执行完整 DMG 打包；修复点位于打包后 Electron 主进程的运行时权限处理，已覆盖当前报错链路。
