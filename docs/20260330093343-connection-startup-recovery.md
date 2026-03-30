# 主题：connection-startup-recovery

## 记录

### 2026-03-30 09:33:43

## 本次目标
- 修复 Redis 连接打开时报“当前连接类型不支持 JDBC SQL 连接: REDIS”的回归问题。
- 恢复新建连接时数据库类型/数据库选择能力，避免仅显示 MySQL 或缺少数据库选择控件。
- 修复应用启动时后端稍慢导致前端无法自动获取最新连接与数据库类型数据的问题。

## 关键改动
- 前端 `apps/desktop/src/modules/studio/composables/useStudioRuntime/connections.ts` 新增内置数据库类型兜底表，覆盖 MySQL/PostgreSQL/SQL Server/Oracle/SQLite/Redis/MongoDB 的最小运行规格；即使 `/api/connection/db-types` 尚未返回，前端仍能正确识别 KV/关系型类型、多数据库能力和默认端口。
- 前端 `apps/desktop/src/modules/studio/composables/useStudioRuntime/state.ts` 将支持的数据库类型初始值改为兜底列表，避免新建连接弹窗在启动早期只剩默认 MySQL 或丢失数据库选择能力。
- 前端 `apps/desktop/src/modules/studio/composables/useStudioRuntime/lifecycle.ts` 增加启动补偿重试：首次加载数据库类型和连接列表失败后会在启动阶段继续轮询重试，后端稍慢时能自动补齐最新数据，而不是停留在旧状态直到手工刷新。
- 后端 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/SchemaServiceImpl.java` 为 `listDatabases` 增加 MongoDB/Redis 分支保护：即使前端误走 `/api/schema/databases`，也不会再把 KV 连接打到 JDBC `openTargetConnection()` 上。
- 新增回归测试 `apps/server/src/test/java/com/sqlcopilot/studio/service/impl/SchemaServiceImplKvDatabasesTest.java`，锁定 Redis 数据库列表不再误走 JDBC 的行为。

## 验证结果
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端定向测试通过：`mvn -f apps/server/pom.xml -Dtest=SchemaServiceImplKvDatabasesTest test -Dfile.encoding=UTF-8`
- 后端打包通过：`mvn -f apps/server/pom.xml clean package -DskipTests -Dfile.encoding=UTF-8`
- 后端 clean 启动通过：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18180' -Dfile.encoding=UTF-8`
- 健康检查通过：`curl --noproxy '*' http://127.0.0.1:18180/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 数据库类型接口验证通过：`curl --noproxy '*' http://127.0.0.1:18180/api/connection/db-types` 返回 PostgreSQL/MySQL/SQLite/Oracle/SQL Server/MongoDB/Redis 等完整类型，不再只有 MySQL。
- 前端 preview 通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6080 --strictPort`，`curl -I http://127.0.0.1:6080/` 返回 `HTTP/1.1 200 OK`。

## 遗留项
- 当前仓库已有一个非本次改动引入的失败用例：`RagRetrievalServiceImplTest.retrievePromptContext_supplementsExplicitFocusTableAndUsesCompactQuery`，因此完整 `mvn clean package`（含全量测试）仍会失败；本次已通过定向回归测试和 `-DskipTests` 打包完成交付验证，后续可单独处理该既有测试问题。


### 2026-03-30 10:08:54

## 追加记录

### 2026-03-30 09:36:00

## 本次补充目标
- 修复首屏连接列表请求虽然成功，但仍需要用户手动点一次刷新才能看到最新连接的问题。

## 补充改动
- 更新 `apps/desktop/src/modules/studio/composables/useStudioRuntime/lifecycle.ts`。
- 在启动阶段原有“请求失败时重试”之外，再增加“成功后静默稳定化刷新”机制：首屏初始化成功后，继续按固定间隔自动补偿刷新 `db-types` 与 `connection/list` 多轮。
- 这层补偿专门覆盖“后端 health 已通过，但连接相关初始化/迁移刚完成或尚未完全稳定”的窗口期，避免首屏停留在旧列表，必须手工刷新一次才恢复。

## 补充验证结果
- 前端类型检查再次通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建再次通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`


### 2026-03-30 10:34:40

## 追加记录

### 2026-03-30 09:40:00

## 本次补充目标
- 修复桌面端窗口打开过早，导致首屏连接列表仍需手动刷新一次的问题。

## 补充改动
- 更新 `apps/desktop/electron/main.cjs`。
- 将 Electron 主进程的后端就绪判定从单纯探测 `/api/health`，提升为同时校验 `/api/connection/db-types` 与 `/api/connection/group/list`。
- 只有当数据库类型接口和默认连接分组都已可用时才视为后端真正 ready，再创建窗口；这样可以避免窗口在连接相关迁移/初始化尚未完成时提前打开，首屏拿到不完整连接列表。

## 补充验证结果
- Electron 主进程语法检查通过：`node -c apps/desktop/electron/main.cjs`
- 前端构建再次通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`


### 2026-03-30 10:40:06

## 追加记录

### 2026-03-30 09:45:00

## 本次补充目标
- 修复首屏控制台报 `defaultPortForDbType is not a function` 与 `syncViewportSize is not a function`，导致初始化 watcher 和生命周期提前崩溃的问题。

## 补充改动
- 更新 `apps/desktop/src/modules/studio/composables/useStudioRuntime/state.ts`。
- 将 `syncViewportSize` 与 `defaultPortForDbType` 明确加入 `createStudioRuntimeState()` 的返回对象，保证 `useStudioRuntime()` 组装出的运行时实例包含生命周期与连接表单 watcher 依赖的完整方法集。
- 这次修复后，首屏自动加载链路不会因为运行时对象缺方法而在启动早期中断；之前“手动刷新按钮还能工作”的现象，本质上就是因为按钮走的是另一条没有触发这两个缺失方法的动作链。

## 补充验证结果
- 前端类型检查再次通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建再次通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`


### 2026-03-30 11:00:08

## 追加记录

### 2026-03-30 09:49:00

## 本次补充目标
- 修复启动阶段控制台报 `loadSupportedDbTypes` 缺失，导致初始化重试循环异常、页面隔几秒出现一次“自动刷新”效果的问题。

## 补充改动
- 更新 `apps/desktop/src/modules/studio/composables/useStudioRuntime/state.ts`。
- 将 `loadSupportedDbTypes` 明确加入 `createStudioRuntimeState()` 的返回对象，保证生命周期中的启动引导逻辑能够正常调用数据库类型加载函数。
- 修复后，启动阶段只会在真实请求失败时进行有限次补偿重试，不会再因为方法缺失把首屏初始化卡成周期性抖动。

## 补充验证结果
- 前端类型检查再次通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端构建再次通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
