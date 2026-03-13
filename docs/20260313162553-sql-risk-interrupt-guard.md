# 主题：sql-risk-interrupt-guard

## 记录

### 2026-03-13 16:25:53

## 本次目标
- SQL 风险评估在“无条件查全表”场景下接入表行数统计进行二次判断：1 万行以下维持原评级，1 万行及以上提升风险级别。
- 前端点击“停止执行 SQL”时，真正中断后端 JDBC Statement/Connection，避免后台继续传输结果集。

## 关键改动
- `RiskEvaluateReq` 新增可选 `databaseName`，前端风险评估请求会带上当前查询页签数据库上下文。
- `SqlServiceImpl` 在原有 `FULL_SCAN` 命中后，使用 JSqlParser 解析关联表，再结合 `SchemaService.getTableStats()` 的表统计数据做二次判断；若命中大表（>= 10000 行），新增 `LARGE_FULL_SCAN` 高风险项并将整体风险抬到 `HIGH`。
- 表统计为空时，回退读取 schema 概览中的表行数估算，避免冷启动状态下完全失去抬级能力。
- 新增后端接口 `POST /api/sql/interrupt`，请求体为 `SqlInterruptReq`，响应为 `SqlInterruptVO`。
- `SqlServiceImpl.execute()` 执行期间会按 `sessionId` 登记正在运行的 JDBC `Statement/Connection`；收到 interrupt 请求后会先 `Statement.cancel()`，再尝试 `Connection.abort()`/`close()`，并将本次执行标记为“已中断”。
- 前端查询模块点击“停止执行”或关闭正在执行的查询页签时，除了本地 `AbortController.abort()`，还会额外调用 `/api/sql/interrupt`，确保数据库侧真正停止。

## 验证结果
- `npm run -w @sqlcopilot/desktop type-check` 通过。
- `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- `mvn -f apps/server/pom.xml clean package -Dfile.encoding=UTF-8` 通过。
- 后端 clean 启动验证：`mvn -f apps/server/pom.xml clean spring-boot:run -Dfile.encoding=UTF-8 -Dspring-boot.run.arguments=--server.port=18082` 启动成功，`http://127.0.0.1:18082/api/health` 返回 `ok`。
- 前端 preview 验证：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6075` 返回 `HTTP 200`。

## 备注
- 本轮 clean 启动使用了 `18082`/`6075`，避免打断本地默认端口上可能已有的运行实例。


### 2026-03-13 16:39:06

## 本次目标
- 调整无 `WHERE` 查询的风险判断：存在 `LIMIT/TOP/FETCH` 等明确返回条数限制时，不应直接升为高风险；只有限制后的返回条数仍大于 `10000` 时才继续按大表高风险处理。

## 关键改动
- 在 `SqlServiceImpl` 中新增查询返回条数上限解析逻辑，支持识别 `LIMIT n`、`LIMIT offset, count`、`LIMIT count OFFSET offset`、`FETCH FIRST/NEXT n ROWS ONLY`、`TOP n`。
- `raiseRiskForLargeTableFullScan(...)` 现在会先检查语句自身的返回条数上限；若上限存在且 `<= 10000`，则保留原有 `FULL_SCAN` 中风险提示，但不再追加 `LARGE_FULL_SCAN` 高风险项。
- 当语句未限制返回条数，或限制值本身仍大于 `10000` 时，才继续结合表统计行数判断是否升级为 `HIGH`。

## 验证结果
- `mvn -f apps/server/pom.xml clean package -Dfile.encoding=UTF-8` 通过。
- `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动验证：`mvn -f apps/server/pom.xml clean spring-boot:run -Dfile.encoding=UTF-8 -Dspring-boot.run.arguments=--server.port=18083` 启动成功，`http://127.0.0.1:18083/api/health` 返回 `ok`。
- 前端 preview 验证：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6076` 返回 `HTTP 200`。


### 2026-03-13 16:44:16

## 本次目标
- 将 `WHERE 1=1`、`WHERE TRUE`、`WHERE id = id` 这类伪过滤条件纳入风险判断，并继续沿用“`LIMIT/TOP/FETCH <= 10000` 不升高风险”的规则。

## 关键改动
- `SqlServiceImpl` 新增基于 JSqlParser 的“有效过滤”判断，不再只看 SQL 是否包含 `where` 关键字。
- 对 `WHERE` 条件整体恒真的场景按“缺少有效过滤条件”处理，覆盖：
  - `WHERE 1 = 1`
  - `WHERE TRUE`
  - `WHERE id = id`
  - 以及全由这些恒真条件通过 `AND/OR` 组合出来的伪过滤表达式。
- 若语句带有 `LIMIT/TOP/FETCH` 且返回上限 `<= 10000`，仍只保留中风险 `FULL_SCAN` 提示，不追加 `LARGE_FULL_SCAN` 高风险项。
- 风险提示文案同步从“缺少 where 条件”调整为“缺少有效过滤条件”，避免和伪过滤场景冲突。

## 验证结果
- `mvn -f apps/server/pom.xml clean package -Dfile.encoding=UTF-8` 通过。
- `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动验证：`mvn -f apps/server/pom.xml clean spring-boot:run -Dfile.encoding=UTF-8 -Dspring-boot.run.arguments=--server.port=18084` 启动成功，`http://127.0.0.1:18084/api/health` 返回 `ok`。
- 前端 preview 验证：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6077` 返回 `HTTP 200`。
