# 主题：table-copy-cross-database-logging

## 记录

### 2026-03-12 16:05:36

## 2026-03-12 同连接跨库复制与日志补强

### 本次目标
- 修复 MySQL 同连接跨库复制在 fast path 不可用时退回 JDBC 双连接搬运，导致数据复制阶段可能出现 `Data truncated for column 'id' at row 1` 的问题。
- 为表复制全链路补充必要日志，便于定位命中分支、失败阶段和执行结果。

### 关键改动
- 调整 `TableCopyServiceImpl` 的数据复制分支：只要源/目标仍是同一连接，就改走单连接显式列 `INSERT INTO ... SELECT ...` 复制；MySQL 跨库场景使用带库名的限定表名，避免再退回 `setObject` 批量写入路径。
- 保留跨连接异步搬运逻辑，但补充开始、进度、完成、失败日志。
- 为表复制入口、异步任务入队/执行、fast path 命中与跳过、目标表创建、源表行数统计、复制完成与异常回滚增加结构化日志，日志中统一输出源/目标连接、库表、复制模式、是否同连接/同库等上下文。

### 验证结果
- 后端构建：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18088"` 成功。
- 健康检查：`curl --noproxy '*' http://127.0.0.1:18088/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6062 --strictPort` 成功，`curl --noproxy '*' -I http://127.0.0.1:6062/` 返回 `HTTP/1.1 200 OK`。

### 说明
- `git status` 中存在用户已有的 `.skills/backend-api-design/SKILL.md` 未纳入本次修改。


### 2026-03-12 17:11:22

## 2026-03-12 17:11 表复制异步后处理与进度反馈
- 后端将“复制结构和数据”统一切换为异步任务，同连接复制也返回任务进度，避免前端在请求阶段等待缓存刷新和后续向量化。
- 表复制完成后不再同步执行 `refreshSchemaCache` 和单表向量化，改为后台后处理线程先刷新目标库缓存，再通过统一向量化队列提交单表任务。
- 向量化队列新增单表入队能力，单表变更链路改为入队执行，避免同步阻塞请求线程。
- 前端表复制成功后立即给出结果提示，页面刷新改为非阻塞的当前页范围刷新，并保留任务弹窗的阶段、行数和进度展示。
- 验证：`mvn -f apps/server/pom.xml clean package -DskipTests`、`npm run type-check`、`npm run build -- --emptyOutDir` 通过；`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18100"` 启动成功，`/api/health` 返回 `ok`；`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6074 --strictPort` 可访问并返回 `HTTP 200`。
