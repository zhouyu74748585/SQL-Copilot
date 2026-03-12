# 主题：object-browser-refresh-cache

## 记录

### 2026-03-12 16:10:01

## 2026-03-12 对象浏览刷新强制回源

### 本次目标
- 修复对象浏览页刷新按钮仍可能复用本地缓存的问题。
- 让刷新按钮执行真实请求，并用最新返回结果覆盖本地缓存与当前详情面板。

### 关键改动
- `StudioShell.vue` 中对象浏览页刷新按钮改为调用 `refreshCurrentObjects({ force: true })`，显式走强制刷新路径。
- `useStudioRuntime.ts` 为 `prepareConnectionTreeData`、`loadDatabaseListForConnection` 增加 `force` 参数，支持数据库列表跳过缓存直接重新请求。
- `refreshCurrentObjects` 增加强制刷新逻辑：点击刷新时先失效当前连接/数据库相关缓存，再重新拉取数据库列表、当前对象列表，并刷新当前已选对象详情。
- `loadOverview` 增加表统计强制刷新参数，避免对象浏览页刷新后表行数/大小仍沿用节流窗口内的旧统计数据。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18091"` 成功。
- 健康检查：`curl --noproxy '*' http://127.0.0.1:18091/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6065 --strictPort` 成功，`curl --noproxy '*' -I http://127.0.0.1:6065/` 返回 `HTTP/1.1 200 OK`。

### 说明
- 工作区中同时存在用户已有未提交改动：`.skills/backend-api-design/SKILL.md`、`apps/server/src/main/java/com/sqlcopilot/studio/service/impl/TableCopyServiceImpl.java`、`docs/20260312160536-table-copy-cross-database-logging.md`，本次未处理这些文件。


### 2026-03-12 16:37:34

## 2026-03-12 浏览页刷新范围收敛

### 本次目标
- 将对象浏览页顶部刷新按钮改为只刷新当前页面范围的数据，不触发左侧导航树和数据库列表的整体刷新。
- 典型场景：表列表页点击刷新时，只刷新中间表列表与当前详情，不去重建左侧导航树。

### 关键改动
- 前端 `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 新增 `refreshCurrentPageObjects`，专门负责当前浏览页数据刷新，不再调用连接树重建和连接/库级缓存失效逻辑。
  - `loadOverview` 增加 `syncTreeCaches` 开关，页面刷新表列表时只更新 `schemaOverview`，不回写左树使用的表名缓存。
  - 为对象浏览页新增独立的当前列表数据源：非表对象列表与保存查询列表不再依赖左树缓存回流，页面刷新可只更新当前视图。
  - 补充左树直接选中对象时对页面列表数据的同步，避免拆分后出现中间列表空白。
- 前端 `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 对象浏览页刷新按钮改为调用 `refreshCurrentPageObjects({ force: true })`。

### 验证结果
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18097"` 成功，`/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6071 --strictPort` 成功，`curl -I http://127.0.0.1:6071/` 返回 `HTTP/1.1 200 OK`。
