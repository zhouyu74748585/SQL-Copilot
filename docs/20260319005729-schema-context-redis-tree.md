# 主题：schema-context-redis-tree

## 记录

### 2026-03-19 00:57:29

## ????
- ?? Redis ??????????????
- ?? PostgreSQL / SQL Server / Oracle ????????? `database -> schema -> table`?
- ?? SQL Server ????????? schema ????
- ???????? title ?????????????????

## ????
- ???? `apps/server/src/main/java/com/sqlcopilot/studio/support/SchemaContextSupport.java`????? `database::schema` ??????
- ???? `apps/server/src/main/java/com/sqlcopilot/studio/dto/schema/SchemaNamespaceVO.java` ? `GET /api/schema/namespaces`?????? schema ???
- ???? `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/SchemaServiceImpl.java`?
  - `listDatabases()` ???????????SQL Server ???? `sys.schemas`?
  - `listNamespaces()` ?? schema ?????
  - ?????????????? JDBC ??????? `database::schema`?
- ???? `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/SqlServiceImpl.java` ? `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/TableDataServiceImpl.java`??? SQL / ????????? catalog/schema?
- ???? `apps/server/src/main/java/com/sqlcopilot/studio/service/kv/KvRuntimeClientFactory.java` ? `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/KvServiceImpl.java`?Redis ??????????????????????
- ???? `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`?
  - ????? `database-root -> schema -> category -> object`?
  - ?? namespace ??? schema context ???
  - ??????? `name`???? title ????????
  - ??/???/??? tab ???????????????????
- ???? `apps/desktop/src/modules/studio/composables/useConnectionBrowserModule.ts`?
  - schema ????????? `namespaceName`?
  - create/rename/drop schema ?????????????
- ?? `apps/server/src/main/resources/jdbc-drivers.yml` ? PostgreSQL ? schema ?? SQL??? `information_schema.schemata`?
- ?? Windows ????????? `NO_PROXY=...` ????? `cmd.exe` ?????

## ????
- `mvn -f apps/server/pom.xml clean package -DskipTests`????
- `mvn -f apps/server/pom.xml -DskipTests compile`????
- `npm run -w @sqlcopilot/desktop type-check`????
- `npm run -w @sqlcopilot/desktop build`????
- ?????????`npx vite preview --host 127.0.0.1 --port 8891` ?????????????
- ????????? `http://127.0.0.1:18080/api/connection/list` ?? 200?
- SQL Server ?????
  - `GET /api/schema/databases?connectionId=2` ??????? `localDb/master/model/msdb/tempdb`???? schema ?????
  - `GET /api/schema/namespaces?connectionId=2&databaseName=localDb` ?? `cdc/dbo`?
  - `GET /api/schema/overview?connectionId=2&databaseName=localDb::dbo` ?? `dbo` ?????????????

## ????
- Redis ?? 1 ??? `GET /api/kv/overview?connectionId=1&databaseName=0` ????? `Unable to connect to 127.0.0.1/<unresolved>:6379`?
- ????????? `127.0.0.1:6379` ?????????? Redis ?????/??????????????????? Redis ??????????????????

### 2026-03-19 01:20:00

## 本轮补充
- 调整 Redis 对象浏览页布局，去掉原有列表/卡片并存的切换入口。
- Redis 对象浏览区域固定为“左侧层级结构 + 右侧键列表表格”。
- Redis 场景下不再展示卡片视图，避免与层级树重复表达同一批对象。

## 涉及文件
- `apps/desktop/src/modules/studio/components/StudioShell.vue`

## 验证补充
- `npm run -w @sqlcopilot/desktop type-check`：通过。
- `npm run -w @sqlcopilot/desktop build`：通过。
- 待继续执行 clean 后的前后端启动验证。

### 2026-03-19 07:40:00

## 本轮补充
- 修复 Redis 连接树读取数据库列表时再次误走 JDBC 路径的问题；桌面端现在直接为 Redis 连接回填逻辑库 `0`（或连接配置中的逻辑库），不再触发关系型数据库的库列表读取。
- 补齐 schema 分层树的右键菜单处理：新增 `databaseRoot` 节点右键识别，恢复库 / schema 节点上的创建入口。
- 增加库级与 schema 级创建动作：
  - 前端新增“新建库”“新建 Schema”动作，并按节点上下文选择对应接口。
  - 后端新增 `POST /api/schema/database/create` 与 `POST /api/schema/schema/create`。
- 补齐“新建下级”中的新建视图、新建函数入口，并为视图/函数相关右键菜单增加稳妥的能力判断回退。
- 修复 `database::schema` 上下文在 `getActiveDatabaseName` 中被误判为不可见的问题，避免 schema 级对象操作丢失上下文。

## 涉及文件
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
- `apps/desktop/src/modules/studio/composables/useConnectionBrowserModule.ts`
- `apps/desktop/src/types/index.ts`
- `apps/server/src/main/java/com/sqlcopilot/studio/controller/SchemaController.java`
- `apps/server/src/main/java/com/sqlcopilot/studio/service/SchemaService.java`
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/SchemaServiceImpl.java`
- `apps/server/src/main/java/com/sqlcopilot/studio/dto/schema/SchemaDatabaseCreateReq.java`
- `apps/server/src/main/java/com/sqlcopilot/studio/dto/schema/SchemaSchemaCreateReq.java`

## 验证补充
- `npm run -w @sqlcopilot/desktop type-check`：通过。
- `mvn -f apps/server/pom.xml -DskipTests compile`：通过。
- 待继续执行 clean 打包与启动验证。


### 2026-03-19 23:08:33

## 本次目标
- 修复 Redis 对象浏览页展开下一级时图标变化但子节点不显示的问题。

## 关键改动
- 修正 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/KvServiceImpl.java` 中 Redis `SCAN` 游标处理，直接复用 Lettuce 返回的 `KeyScanCursor`，避免丢失 `isFinished()` 状态。
- 同步修复 Redis 浏览搜索、普通浏览、路径删除扫描和键概览扫描中的同类游标问题，避免出现 `finished=false` 但 `nextCursor=0` 的异常分页状态。
- 调整 `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`，为 Redis 路径节点预置空 `children`，确保 Ant Design Vue 树表格在异步回填子节点后能稳定展开。

## 验证结果
- `mvn -f apps/server/pom.xml clean package -DskipTests`：通过。
- `npm run -w @sqlcopilot/desktop type-check`：通过。
- `npm run -w @sqlcopilot/desktop build`：通过。
- 后端重新启动成功，`http://127.0.0.1:18080/actuator/health` 返回 UP。
- 前端 preview 启动成功，`http://127.0.0.1:8891/` 返回 200。
- Redis 浏览接口回归通过：`parentPath=a:v:cd` 返回键 `a:v:cd:ex`，且 `finished=true`、`nextCursor=0`。

## 备注
- 当前验证使用本地 Redis 连接 `connectionId=4` 进行接口回归。


### 2026-03-19 23:22:39

## 本轮补充
- 继续修复 Redis 对象浏览页“接口已返回子节点但前端未展开”的问题。
- 调整 `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts` 中 `handleRedisBrowserExpand` 的执行时序：
  - 非搜索模式下先异步加载子节点。
  - 等待下一轮渲染后再写入 `redisExpandedRowKeys`。
  - 避免树表格在 children 尚未挂载时提前进入“空展开”状态，只改图标不展开内容。
- 保留路径节点的空 `children` 占位，和新的展开时序配合，提升 Redis 树表异步展开稳定性。

## 验证补充
- `npm run -w @sqlcopilot/desktop type-check`：通过。
- `npm run -w @sqlcopilot/desktop build`：通过。
- `http://127.0.0.1:8891/`：返回 200。
- `http://127.0.0.1:18080/actuator/health`：返回 UP。

## 说明
- 本轮尝试过无头页面回归，但受限于本地预览页在无头 iframe 场景下无法稳定读取连接树内容，未能完成自动点击验证；当前修复依据是组件渲染时序与 Ant Design Vue 树表行为的对应分析。


### 2026-03-19 23:48:38

## 本轮补充
- 使用 `$playwright-interactive` 技能思路进行页面检查；由于当前会话无 `js_repl` 工具，按技能说明降级为临时 Playwright 脚本回归。
- 通过 Playwright 复现到真实问题链路：
  - 点击 Redis 路径行 `a` 时，请求 `GET /api/kv/redis/browser?parentPath=a` 已成功返回。
  - 但 `StudioShell.vue` 中 `a-table` 没有真正消费受控展开状态，导致用户可见图标变化后仍不渲染子节点。
- 修复 `apps/desktop/src/modules/studio/components/StudioShell.vue`：
  - 不再使用 `:expandable="redisTableExpandable"` 聚合对象传递 Redis 树表展开配置。
  - 改为直接绑定 `expanded-row-keys`、`children-column-name`、`row-expandable`、`show-expand-column`，并直接监听 `@expand`。
  - 移除 Redis 表格中不可见但仍会干扰交互的默认展开按钮 DOM，让路径展开完全走我们自己的受控状态。

## 验证补充
- `npm run -w @sqlcopilot/desktop type-check`：通过。
- `npm run -w @sqlcopilot/desktop build`：通过。
- Playwright 回归通过：
  - 初始 Redis 表格行为：仅显示路径 `a`。
  - 单击 `a` 后：表格成功展开出子节点 `v`。
  - 再次双击 `a` 后：子节点收起，展开/收起行为恢复正常。
- `http://127.0.0.1:18080/actuator/health`：返回 UP。

## 说明
- 由于桌面端前端固定请求 `http://localhost:18080`，浏览器预览模式存在跨域限制；本轮 Playwright 验证通过关闭浏览器同源限制来模拟 Electron 中的真实渲染行为。


### 2026-03-20 06:54:50

## 本轮补充
- 调整 Redis 对象浏览表格的层级缩进，让展开后的下一级相对上一级有明确视觉层次。
- 修改 `apps/desktop/src/modules/studio/components/StudioShell.vue`：
  - 为 Redis `nodeName` 单元格增加 `redisRowIndentStyle(record)` 绑定。
  - 按路径深度计算缩进：根节点不缩进，子节点每级增加 18px 左侧缩进。
  - `KEY`、`PATH`、`LOAD_MORE` 三类 Redis 行统一按当前树深度展示，避免展开后层级平铺。

## 验证补充
- `npm run -w @sqlcopilot/desktop type-check`：通过。
- `npm run -w @sqlcopilot/desktop build`：通过。
- `mvn -f apps/server/pom.xml clean package -DskipTests`：通过。
- 后端重新启动成功，`http://127.0.0.1:18080/actuator/health` 返回 UP。
- 前端 preview 可访问，`http://127.0.0.1:8891/` 返回 200。
- Playwright 缩进回归通过：
  - `a` 行 `padding-left = 0px`，图标 `x = 280`，文字 `x = 302`。
  - `v` 行 `padding-left = 18px`，图标 `x = 298`，文字 `x = 320`。
  - 说明展开后的下一级相对上一级产生了 18px 的有效缩进。
