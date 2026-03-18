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
