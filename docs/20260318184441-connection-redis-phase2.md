# 主题：connection-redis-phase2

## 记录

### 2026-03-18 18:44:41

## 本次目标
- 完成连接管理与 Redis 浏览二期改造的前后端收口。
- 补齐连接分组、拖拽、运行态状态、Redis 层级浏览与键 CRUD 的前端接线。
- 验证 SQL Server 默认 SSL 兼容、配置驱动数据库预览与 KV 类型跳过表统计的整体可构建性。

## 关键改动
- 后端连接管理改为配置驱动的数据库预览：`apps/server/src/main/java/com/sqlcopilot/studio/support/JdbcDriverResolver.java` 与 `apps/server/src/main/resources/jdbc-drivers.yml` 新增/接入 `connectionPreview` 配置，`apps/server/src/main/java/com/sqlcopilot/studio/service/impl/ConnectionServiceImpl.java` 统一走 YAML 配置获取库列表。
- 后端新增连接分组能力：`apps/server/src/main/resources/schema.sql`、`apps/server/src/main/java/com/sqlcopilot/studio/support/ConnectionSchemaMigrationRunner.java`、`apps/server/src/main/java/com/sqlcopilot/studio/entity/ConnectionGroupEntity.java`、`apps/server/src/main/java/com/sqlcopilot/studio/mapper/ConnectionGroupMapper.java` 以及 `ConnectionGroup*Req/VO`、`ConnectionController`、`ConnectionServiceImpl` 支持默认分组、创建/重命名/删除/移动分组。
- 后端 Redis 键 CRUD 已落地并补前端接线：`apps/server/src/main/java/com/sqlcopilot/studio/service/impl/KvServiceImpl.java`、`apps/server/src/main/java/com/sqlcopilot/studio/controller/KvController.java` 支持 `string/hash/list/set/zset` 新增/修改/删除；前端 `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts` 与 `apps/desktop/src/modules/studio/components/StudioShell.vue` 新增 Redis 键弹窗、右侧详情编辑入口与刷新联动。
- 连接运行态状态改为最近一次加载下级结果驱动：`apps/desktop/src/modules/studio/composables/useStudioRuntime.ts` 统一维护 `connectionRuntimeStatusMap`，连接成功拉库置绿、拉取失败置红、关闭连接置灰。
- 前端连接树改为 `分组 -> 连接`：`apps/desktop/src/modules/studio/composables/useStudioRuntime.ts` 生成分组树并支持空分组占位、跨分组拖拽；`apps/desktop/src/modules/studio/components/StudioShell.vue` 增加新建分组按钮、连接主副标题显示与分组详情面板。
- 新建/编辑连接表单改为能力驱动渲染：`apps/desktop/src/modules/studio/composables/useStudioRuntime.ts` 基于 `ConnectionDbTypeVO` 能力字段驱动字段显隐；`apps/desktop/src/modules/studio/components/StudioShell.vue` 按数据库类型显示主机/端口/账号/库名/预览按钮，并补充 SQL Server 默认 SSL 提示。
- Redis 浏览中间区域升级为“层级结构 + 列表”：`apps/desktop/src/modules/studio/composables/useStudioRuntime.ts` 增加 `redisHierarchyTreeData`、`redisVisibleObjectRows`、`handleRedisHierarchySelect`；`apps/desktop/src/modules/studio/components/StudioShell.vue` 增加层级树、列表区与详情区联动。
- 环境标识图标已统一：前端运行时 `envTagIcon` 使用开发 `ToolOutlined`、测试 `ExperimentOutlined`、生产 `SafetyCertificateOutlined`。
- 前端中英词典补充本轮新增文案：`apps/desktop/src/i18n/messages.ts`。

## 验证结果
- 前端类型检查通过：`npm run type-check`
- 前端构建通过：`npm run build`
- 后端 Maven clean 打包通过：`mvn -f apps/server/pom.xml clean package`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean spring-boot:run`，日志显示服务监听 `http://127.0.0.1:18080`
- 前端预览成功：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
- 探活成功：`curl http://127.0.0.1:18080/actuator` 返回 actuator JSON，`curl -I http://127.0.0.1:4173/` 返回 `HTTP/1.1 200 OK`

## 遗留项
- 当前已完成构建与启动验证，但尚未对“连接分组拖拽、Redis 层级导航、Redis 键 CRUD、SQL Server 默认 SSL 覆盖”等场景做完整人工点击回归。
- `apps/server/src/main/java/com/sqlcopilot/studio/dto/connection/ConnectionUpdateReq.java` 在 Maven 编译阶段仍有 Lombok `equals/hashCode` 的既有 warning，本轮未扩散处理。


### 2026-03-19 10:09:24

## 本次目标
- 修复 SQL Server 读取数据库列表时因服务端仅支持 TLS1.0 导致的握手失败。
- 修复 Redis 连接树再次只展示单个逻辑库的问题。

## 关键改动
- `apps/server/src/main/java/com/sqlcopilot/studio/SqlCopilotApplication.java`
  - 应用启动期预置 legacy TLS 兼容配置，允许老旧数据库服务器协商 `TLSv1/TLSv1.1`。
  - 新增可选关闭开关 `-Dsqlcopilot.legacy-tls.enabled=false`。
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/ConnectionServiceImpl.java`
  - SQL Server 建连改为三段兼容策略：默认 SSL -> 非加密降级 -> legacy TLS 重试。
  - Redis 预览库列表改为复用统一的 Redis 库枚举逻辑。
- `apps/server/src/main/java/com/sqlcopilot/studio/service/kv/KvRuntimeClientFactory.java`
  - 新增 Redis 逻辑库枚举能力，优先通过 `CONFIG GET databases` 读取真实库数，失败时回退默认范围。
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/KvServiceImpl.java`
  - Redis `/api/kv/databases` 改为走统一库枚举逻辑，不再写死单一路径。
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 去掉 Redis 数据库列表的单库硬编码，恢复按后端返回的逻辑库列表渲染连接树。

## 验证结果
- 前端类型检查通过：`npm run type-check`
- 前端构建通过：`npm run build`
- 后端 Maven clean 打包通过：`mvn -f apps/server/pom.xml clean package`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18084`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
- 探活通过：`curl http://127.0.0.1:18084/actuator`
- Redis 实测通过：`curl http://127.0.0.1:18084/api/kv/databases?connectionId=2` 返回 `0-15` 共 16 个逻辑库。
- SQL Server 实测通过：`curl http://127.0.0.1:18084/api/schema/databases?connectionId=3` 成功返回 `master/model/msdb/tempdb/WyglDB...` 等数据库列表。

## 遗留项
- 当前为兼容老旧 SQL Server，后端默认开启了 legacy TLS 能力；如目标环境不需要兼容 TLS1.0/TLS1.1，可通过 `-Dsqlcopilot.legacy-tls.enabled=false` 或环境变量 `SQLCOPILOT_LEGACY_TLS_ENABLED=false` 关闭。
- 本轮验证发现 `18080` 端口已被现有进程占用，因此 clean 启动验证改在 `18084` 完成；当前保留 `18084` 后端实例和 `4173` 前端预览实例供继续回归。


### 2026-03-19 11:42:22

## 本次目标
- 将 Redis 中间区域改为单一可展开树表格，保留右侧详情展示。
- 将 Redis 键浏览与搜索从全量拉取改为服务端按需扫描，降低大库下的卡顿。

## 关键改动
- `apps/server/src/main/java/com/sqlcopilot/studio/controller/KvController.java`
  - 新增 `GET /api/kv/redis/browser`，支持 `connectionId/databaseName/parentPath/keyword/cursor/pageSize` 查询。
- `apps/server/src/main/java/com/sqlcopilot/studio/service/KvService.java`
  - 新增 Redis 树表浏览服务方法。
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/KvServiceImpl.java`
  - Redis 浏览改为“当前路径 + 服务端分页 SCAN”。
  - 返回直接子节点（PATH/KEY），并对当前页 KEY 批量补齐 `TYPE/TTL`。
  - 增加最大扫描轮次与较大批次扫描，避免根层级为凑满一页而长时间阻塞。
  - Redis `overview` 不再承担中间区全量键扫描职责。
- `apps/server/src/main/java/com/sqlcopilot/studio/dto/kv/KvRedisBrowserPageVO.java`
  - 新增 Redis 树表分页结果 DTO。
- `apps/server/src/main/java/com/sqlcopilot/studio/dto/kv/KvRedisBrowserNodeVO.java`
  - 新增 Redis 树表节点 DTO。
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 新增 Redis 树表状态：根路径、展开行、分页缓存、子节点加载状态。
  - 顶部搜索改为 300ms 防抖后走后端 `/api/kv/redis/browser`。
  - 路径切换、展开子节点、加载更多、刷新当前路径均改为按需请求。
  - 右侧详情继续复用 `GET /api/kv/object/detail`，点击路径节点不清空当前键详情。
- `apps/desktop/src/modules/studio/composables/useConnectionBrowserModule.ts`
  - Redis 行交互改造：PATH 行切换当前路径，LOAD_MORE 行继续分页，KEY 行保留现有详情/右键行为。
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 删除原有“左层级树 + 右列表”布局，改为单一树表格。
  - 树表格列调整为“名称 / 节点类型 / 值类型 / TTL / 说明”。
  - 顶部新增“返回上级 / 根层级”入口。
- `apps/desktop/src/types/index.ts` 与 `apps/desktop/src/i18n/messages.ts`
  - 补齐 Redis 树表相关前端类型与中英双语文案。

## 验证结果
- 前端类型检查通过：`npm run type-check`
- 前端构建通过：`npm run build`
- 后端 Maven clean 打包通过：`mvn -f apps/server/pom.xml clean package`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18086`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4174 --strictPort`
- 探活通过：`curl http://127.0.0.1:18086/actuator`
- Redis 树表接口通过：
  - `GET /api/kv/redis/browser?connectionId=2&databaseName=0&parentPath=&keyword=&cursor=0&pageSize=20` 成功返回根层路径节点。
  - `GET /api/kv/redis/browser?...&parentPath=07061f03-49ce-43f8-bf58-219372285524` 成功返回子层路径节点。
  - `GET /api/kv/redis/browser?...&keyword=account_refresh_token` 成功返回命中搜索结果的路径节点。
  - 递归浏览后 `GET /api/kv/object/detail?connectionId=2&databaseName=0&objectName=account_refresh_token:045fe994-1438-4835-9e07-9cf79ab9123d` 成功返回详情。

## 遗留项
- 当前桌面前端 `apps/desktop/src/api/client.ts` 仍固定请求 `http://localhost:18080`；本轮因 `18080` 已被现有 Java 进程占用，clean 启动验证改在 `18086` 完成，因此 preview 的人工点击联调若要命中新后端，需要先释放 `18080` 或后续将前端 API 基址改为可配置。
- 当前保留了后端 `18086` 与前端 preview `4174` 进程，方便继续人工回归。


### 2026-03-19 13:49:23

## 本次目标
- 将 Redis 树表改为同页内联展开，不再通过点击 PATH 节点进入内层页面。
- 让 PATH 节点支持右键递归删除整棵前缀。
- 将 Redis 键搜索改为全库 Redis glob 通配符搜索。

## 关键改动
- `apps/server/src/main/java/com/sqlcopilot/studio/dto/kv/KvRedisKeyDeleteReq.java`
  - 删除请求从单一 `keyName` 升级为 `targetType + targetValue`。
- `apps/server/src/main/java/com/sqlcopilot/studio/dto/kv/KvRedisKeyDeleteVO.java`
  - 新增 Redis 删除结果对象，返回目标类型、目标值、删除数量与消息。
- `apps/server/src/main/java/com/sqlcopilot/studio/controller/KvController.java`
  - `/api/kv/redis/key/delete` 改为返回结构化删除结果。
- `apps/server/src/main/java/com/sqlcopilot/studio/service/KvService.java`
  - Redis 删除服务签名改为返回 `KvRedisKeyDeleteVO`。
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/KvServiceImpl.java`
  - PATH 删除改为按前缀执行递归扫描与批量删除。
  - Redis 搜索不再转义 `*`、`?`、`[]`，直接按 glob 语法匹配。
  - 搜索结果改为“命中 key + 祖先 PATH 节点”的树化返回。
- `apps/desktop/src/modules/studio/composables/useConnectionBrowserModule.ts`
  - PATH 点击/双击改为同页展开/收起。
  - PATH 节点右键菜单放开，仅提供递归删除入口；LOAD_MORE 仍不弹菜单。
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 搜索态与浏览态分离：搜索态直接构建命中树并自动展开 PATH；浏览态继续保留分支懒加载与展开记忆。
  - Redis 删除统一增加确认弹窗，并按 KEY/PATH 区分提示文案。
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 移除“返回上级 / 根层级”的页面级导航按钮。
  - 顶部改为展示当前 glob 搜索标签；中间树表仅保留同页内联展开。
- `apps/desktop/src/types/index.ts` 与 `apps/desktop/src/i18n/messages.ts`
  - 补齐 Redis 删除结果、请求模型与新增文案映射。

## 验证结果
- 前端类型检查通过：`npm run type-check`
- 前端构建通过：`npm run build`
- 后端 Maven clean 打包通过：`mvn -f apps/server/pom.xml clean package`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18088`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4176 --strictPort`
- 探活通过：`curl http://127.0.0.1:18088/actuator`
- Redis 根层浏览通过：`GET /api/kv/redis/browser?connectionId=2&databaseName=0&parentPath=&keyword=&cursor=0&pageSize=20`
- Redis glob 搜索通过：`GET /api/kv/redis/browser?connectionId=2&databaseName=0&parentPath=&keyword=account_refresh_token*&cursor=0&pageSize=20`
- 非破坏性 KEY 删除验证通过：删除不存在键 `__codex_nonexistent_key__` 返回 `deletedCount=0`

## 遗留项
- 对 live Redis 执行“PATH 不存在前缀”的非破坏性删除验证时，请求在 40s 内未返回；这是因为当前实现按计划使用 `SCAN + 批量 DEL` 做整库前缀扫描，面对大库即使目标不存在也需要完整遍历。
- 当前桌面前端 API 基址仍固定为 `http://localhost:18080`，而本轮 clean 启动验证在 `18088` 完成；因此若要在 preview 中手工联调最新后端能力，需要先释放 `18080` 或后续将前端 API 基址改为可配置。
- 当前保留了后端 `18088` 与前端 preview `4176` 进程供继续回归。


### 2026-03-19 14:12:57

## 本次目标
- 调整 Redis “新增键”入口位置：从右侧详情区移到中间工具栏，并放在“新建查询”旁边。

## 关键改动
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 在 Redis 对象浏览场景下，将“新增键”按钮添加到中间工具栏的“新建查询”右侧。
  - 从右侧详情区的“键操作”中移除“新增键”按钮，保留“编辑键”“删除键”。

## 验证结果
- 前端类型检查通过：`npm run type-check`
- 前端构建通过：`npm run build`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18089`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4177 --strictPort`
- 探活通过：`curl http://127.0.0.1:18089/actuator` 与 `curl -I http://127.0.0.1:4177/`

## 遗留项
- 当前桌面前端 API 基址仍固定指向 `http://localhost:18080`，本轮 clean 启动验证使用的是 `18089`，因此 preview 的人工点击联调若要命中新后端，仍需要释放 `18080` 或后续将前端 API 基址改为可配置。
- 当前保留了后端 `18089` 与前端 preview `4177` 进程，便于继续回归。


### 2026-03-19 14:24:24

## 本次目标
- 让 Redis 树表格的展开按钮默认显示，避免依赖组件默认行为导致展开列不稳定。

## 关键改动
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 为 Redis 树表格的 `expandable` 显式补齐 `showExpandColumn: true`。
  - 固定展开列位置与宽度：`expandIconColumnIndex: 0`、`columnWidth: 52`。

## 验证结果
- 前端类型检查通过：`npm run type-check`
- 前端构建通过：`npm run build`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18089`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4177 --strictPort`
- 探活通过：`curl http://127.0.0.1:18089/actuator` 与 `curl -I http://127.0.0.1:4177/`

## 遗留项
- 当前 preview 仍默认请求 `18080`，而 clean 启动验证使用的是 `18089`；若要继续人工联调最新后端，仍建议后续将前端 API 基址改为可配置。


### 2026-03-19 14:30:15

## 本次目标
- 修复目录节点右键删除误按精确 KEY 删除的问题，确保 PATH 节点能正确按前缀删除。

## 关键改动
- `apps/desktop/src/modules/studio/composables/useConnectionBrowserModule.ts`
  - 在触发右键动作前，先缓存 `redisNodeType`，避免 `closeContextMenu()` 清空状态后把 PATH 误判成 KEY。
  - `copy/edit/delete` Redis 菜单动作统一改为使用缓存后的 `redisNodeType`。

## 验证结果
- 前端类型检查通过：`npm run type-check`
- 前端构建通过：`npm run build`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18090`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4178 --strictPort`
- 探活通过：`curl http://127.0.0.1:18090/actuator` 与 `curl -I http://127.0.0.1:4178/`

## 遗留项
- 当前 preview 仍默认请求 `18080`，而 clean 启动验证使用的是 `18090`；若要继续人工联调最新后端，仍建议后续将前端 API 基址改为可配置。


### 2026-03-19 14:46:06

## 本次目标
- 修复目录节点删除时误走精确 KEY 删除的问题。
- 删除目录时补上路径分隔符，避免误删同前缀但不同层级的键。
- 删除确认后前端不再阻塞等待整个请求返回。

## 关键改动
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/KvServiceImpl.java`
  - PATH 删除改为：先删除精确键 `path`，再按 `path:*` 扫描与删除，不再使用 `path*` 模糊前缀。
  - 这样可避免把 `foobar` 这类同前缀但非子层级键误删。
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - Redis 删除确认弹窗点击“删除”后立即关闭，后台继续执行删除与刷新，避免用户长时间卡在确认态。
  - PATH 删除后的选中详情清理也改为仅匹配精确路径或 `path:` 下级，不再用宽松 `startsWith(path)`。

## 验证结果
- 前端类型检查通过：`npm run type-check && npm run build`
- 后端 Maven clean 打包通过：`mvn -f apps/server/pom.xml clean package`
- 本轮以构建验证为主；当前仍保留之前的 clean 启动实例可继续人工回归。

## 遗留项
- 当前 preview 仍默认请求 `18080`，如果要人工验证最新后端行为，仍建议后续将前端 API 基址改为可配置。
- 目录删除在大库下仍可能耗时，因为后端仍需执行前缀扫描；本轮主要修复的是“误删范围”和“前端等待体验”。


### 2026-03-19 14:54:49

## 本次目标
- 调整 PATH 删除规则：目录节点只删除 `path:*`，不再额外删除精确 `path`。
- 删除确认后立即关闭确认框，避免前端看起来一直在等待。

## 关键改动
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/KvServiceImpl.java`
  - `deleteRedisPath()` 去掉精确键删除，改为仅按 `normalizedPrefix + ":*"` 扫描和删除。
  - 这样目录节点删除严格限定在子层级键上。
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 删除确认弹窗 `onOk` 改为先 `resolve()` 关闭弹窗，再异步执行删除和刷新。
  - 保留删除成功后的提示和树表刷新逻辑。

## 验证结果
- 前端类型检查与构建通过：`npm run type-check && npm run build`
- 后端 Maven clean 打包通过：`mvn -f apps/server/pom.xml clean package`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18091`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4179 --strictPort`
- 探活通过：`curl http://127.0.0.1:18091/actuator` 与 `curl -I http://127.0.0.1:4179/`

## 遗留项
- 目录删除在大库下仍可能耗时，因为后端仍需执行 `SCAN path:*` 全量遍历；本轮修的是误删范围和前端等待体验。
- 当前 preview 仍默认请求 `18080`，若要人工联调最新后端行为，仍建议后续将前端 API 基址改为可配置。


### 2026-03-19 15:40:09

## 本次目标
- 使用 `apps/desktop/src/assets/icons` 中的资源统一替换左侧树和中间对象展示区的图标。
- 确保相同类型对象在左树和中间区使用同一套图标。
- 区分 Redis PATH 节点的展开/收起图标。

## 关键改动
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 新增统一的对象图标映射 `browserObjectIconSrc`。
  - 左树 `treeTitleIconSrc` 增加展开态参数：分组/目录展开时使用打开文件夹图标，收起时使用关闭文件夹图标。
  - 补齐 schema、Redis key、load more 等资源图标映射。
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 左树标题区改为按节点展开态选择图片图标。
  - 中间表格行和卡片统一改为图片图标，不再使用 Ant Design 字体图标。
  - Redis PATH 行图标会随展开/收起在关闭文件夹与打开文件夹之间切换。
- `apps/desktop/src/modules/studio/styles/shell.css`
  - 新增对象行图标与对象卡片图标样式，统一图片尺寸和对齐。

## 验证结果
- 前端类型检查与构建通过：`npm run type-check && npm run build`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18092`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4180 --strictPort`
- 探活通过：`curl http://127.0.0.1:18092/actuator` 与 `curl -I http://127.0.0.1:4180/`

## 遗留项
- 当前 preview 仍默认请求 `18080`，若要人工联调最新后端行为，仍建议后续将前端 API 基址改为可配置。


### 2026-03-19 15:57:13

## 本次目标
- 按要求仅使用 `apps/desktop/src/assets/icons` 下的图标资源。
- 隐藏 Redis 树表格前置加减展开列，改为直接点击目录行展开/收起。
- 将“新建分组 / 新建表 / 新建查询 / 智能ER图 / 新增键”等入口改为图标按钮并通过 hover 显示说明；“新建连接”保留文字。

## 关键改动
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 左侧“新建连接”改为资源图标 + 文字；“新建分组”改为图标按钮 + tooltip。
  - 中间工具栏中的“新建表 / 新建查询 / 智能ER图 / 新增键”统一改为图标按钮 + tooltip。
  - Redis 树表的 `expandable.showExpandColumn` 改为 `false`，不再显示前置加减号展开列。
- `apps/desktop/src/modules/studio/styles/shell.css`
  - 新增工具栏资源图标样式，统一按钮尺寸和图标尺寸。

## 验证结果
- 前端类型检查与构建通过：`npm run type-check && npm run build`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18093`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4181 --strictPort`
- 探活通过：`curl http://127.0.0.1:18093/actuator` 与 `curl -I http://127.0.0.1:4181/`

## 遗留项
- 当前 preview 仍默认请求 `18080`，若要人工联调最新后端行为，仍建议后续将前端 API 基址改为可配置。


### 2026-03-19 16:10:52

## 本次目标
- 让 Redis 目录节点直接点击图标/行展开下级，不再依赖前置加减号。
- 将左侧“我的连接”区域的“新建连接 / 新建分组 / 刷新”移动到标题同一行右侧。
- 将“新建连接”也改成仅显示图标，通过 hover 显示说明。

## 关键改动
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 左侧“我的连接”面板改用 `#extra` 槽位，在标题同一行右侧放置“新建连接 / 新建分组 / 刷新”图标按钮。
  - “新建连接”改为图标按钮 + tooltip，不再直接显示文字。
  - Redis 树表 `expandable.showExpandColumn` 改为 `false`，去掉前置加减号展开列。
  - 中间工具栏中“新建分组 / 新建表 / 新建查询 / 智能ER图 / 新增键”等继续保持图标 + hover 提示。
- `apps/desktop/src/modules/studio/styles/shell.css`
  - 补充标题行工具按钮与图标样式，保证同一行对齐与 hover 呈现一致。

## 验证结果
- 前端类型检查与构建通过：`npm run type-check && npm run build`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18094`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4182 --strictPort`
- 探活通过：`curl http://127.0.0.1:18094/actuator` 与 `curl -I http://127.0.0.1:4182/`

## 遗留项
- 当前 preview 仍默认请求 `18080`，若要人工联调最新后端行为，仍建议后续将前端 API 基址改为可配置。


### 2026-03-19 16:21:53

## 本次目标
- 修复“新建表”按钮 hover 文案错误。
- 进一步按要求把左侧“我的连接”标题行按钮移到同一行右侧，并把“新建连接”改为图标 + hover。

## 关键改动
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 将“新建表”按钮 tooltip 从误用的智能 ER 提示改为“新建表”。
  - 左侧“我的连接”面板改用 `#extra` 槽位承载“新建连接 / 新建分组 / 刷新”按钮。
  - “新建连接”改为图标按钮 + tooltip，不再直接显示文字。
- `apps/desktop/src/modules/studio/styles/shell.css`
  - 复用并补齐工具栏图标按钮样式，保证标题行右侧按钮展示一致。

## 验证结果
- 前端类型检查与构建通过：`npm run type-check && npm run build`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18095`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4183 --strictPort`
- 探活通过：`curl http://127.0.0.1:18095/actuator` 与 `curl -I http://127.0.0.1:4183/`

## 遗留项
- 当前 preview 仍默认请求 `18080`，若要人工联调最新后端行为，仍建议后续将前端 API 基址改为可配置。


### 2026-03-19 16:31:28

## 本次目标
- 将视图、函数、知识中心中的“新建”按钮统一成图标 + hover 提示交互。

## 关键改动
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - “新建视图”改为 `tree-view.png` 图标按钮 + tooltip。
  - “新建函数”改为 `tree-function.png` 图标按钮 + tooltip。
  - 知识中心“新建术语/样例”改为图标按钮 + tooltip，沿用 `tree-add-folder.png` 资源保持统一风格。
  - 相关“新建查询”按钮继续保持图标按钮风格，不再混用文字按钮。

## 验证结果
- 前端类型检查与构建通过：`npm run type-check && npm run build`
- 后端 clean 启动通过：`SQLCOPILOT_DATA_DIR=/Users/zhouyu/IdeaProjects/SQL_Copilot mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18096`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4184 --strictPort`
- 探活通过：`curl http://127.0.0.1:18096/actuator` 与 `curl -I http://127.0.0.1:4184/`

## 遗留项
- 当前 preview 仍默认请求 `18080`，若要人工联调最新后端行为，仍建议后续将前端 API 基址改为可配置。
