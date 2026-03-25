# 主题：table-grid-header-alignment

## 记录

### 2026-03-25 21:09:06

## 本次目标
- 修复数据浏览与查询结果共用表格中，表头与数据行横向错位的问题。

## 关键改动
- 调整 `apps/desktop/src/modules/studio/styles/table-data.css` 中 `.table-data-virtual-grid-body` 的滚动条槽位策略。
- 将 `scrollbar-gutter` 从 `stable both-edges` 改为 `stable`，避免内容区在左侧额外预留槽位，导致表体相对表头整体右移。
- 保留右侧稳定滚动槽位，继续避免滚动条出现/消失时的布局抖动。

## 验证结果
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端 clean 构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18131' '-Dfile.encoding=UTF-8'`
- 后端健康检查通过：`curl -s http://127.0.0.1:18131/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 成功：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6076 --strictPort`
- 前端连通性通过：`curl -I http://127.0.0.1:6076` 返回 `HTTP/1.1 200 OK`
