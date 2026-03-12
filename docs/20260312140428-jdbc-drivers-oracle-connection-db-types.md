# 主题：jdbc-drivers-oracle-connection-db-types

## 记录

### 2026-03-12 14:04:28

## 本次目标
- 在 `jdbc-drivers.yml` 中补充 Oracle 相关配置。
- 让前端“新增连接”的数据库类型来源于后端配置，而不是前端硬编码。

## 关键改动
- `apps/server/src/main/resources/jdbc-drivers.yml`
  - 补充 Oracle 驱动、默认端口、Schema 列表、表/列/主键读取、表重命名、对象定义读取/保存等配置。
  - 补充 SQLite 条目，避免前端改为配置驱动后丢失现有 SQLite 支持。
  - 为 PostgreSQL/MySQL/SQLServer/Oracle/SQLite 增加 `displayName`、`defaultPort`、`supportsSelectedDatabases` 元数据。
- `apps/server/src/main/java/com/sqlcopilot/studio/support/JdbcDriverResolver.java`
  - 新增已配置数据库类型枚举能力，返回 `dbType/displayName/defaultPort/supportsSelectedDatabases`。
  - 连接服务的“是否支持勾选数据库/Schema”判断改为读取 YAML 配置。
- `apps/server/src/main/java/com/sqlcopilot/studio/controller/ConnectionController.java`
  - 新增 `GET /api/connection/db-types`。
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/ConnectionServiceImpl.java`
  - 暴露支持库型列表给前端。
  - Oracle 连接现在支持保存 `selectedDatabases`，与 Schema 预览选择打通。
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 新增启动时加载 `/api/connection/db-types`。
  - 连接类型下拉、默认端口、是否展示数据库/Schema 预览选择改为基于后端配置。
  - 不再硬编码 MySQL/PostgreSQL/SQLite/SQLServer/Oracle 列表。
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/TableCopyServiceImpl.java`
  - 补充带 schema 前缀的 `CREATE TABLE` DDL 改写，兼容 Oracle `DBMS_METADATA.GET_DDL` 返回结果。
- `apps/server/src/main/java/com/sqlcopilot/studio/repository/SchemaObjectDefinitionJdbcRepository.java`
  - 放宽对象定义头部匹配，兼容 Oracle 视图定义中的 `FORCE/EDITIONABLE` 等关键字。

## 验证结果
- `mvn -f apps/server/pom.xml clean package -DskipTests`：通过。
- `npm run type-check`：通过。
- `npm run build -- --emptyOutDir`：通过。
- `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18086"`：启动成功。
- `curl http://127.0.0.1:18086/api/health`：返回 `{"code":0,"message":"success","data":"ok"}`。
- `curl http://127.0.0.1:18086/api/connection/db-types`：返回 PostgreSQL/MySQL/SQLite/Oracle/SQLServer 配置列表。
- `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6060 --strictPort`：启动成功。
- `curl -I http://127.0.0.1:6060/`：返回 `HTTP/1.1 200 OK`。

## 遗留项
- Oracle 驱动包是否已放入 `apps/server/src/main/resources/drivers/oracle/universal/driver.jar` 仍取决于本地资源准备情况；本次只补齐了配置与运行链路。


### 2026-03-12 14:11:07

## 本次目标
- 修正“配置文件没有 SQLite，但新增连接仍可选择 SQLite”的问题。

## 关键改动
- `apps/server/src/main/resources/jdbc-drivers.yml`
  - 删除 `SQLITE` 条目，确保支持库型列表严格来源于当前配置。
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 新增连接表单不再默认使用 `SQLITE`。
  - 当后端返回的支持库型列表中不存在当前 `dbType` 时，直接切到配置中的第一个库型，不再优先回退到 `SQLITE`。
  - 默认连接名称与数据库名改为通用初始值，避免在未加载配置前带出 SQLite 语义。

## 验证结果
- `mvn -f apps/server/pom.xml clean package -DskipTests`：通过。
- `npm run type-check`：通过。
- `npm run build -- --emptyOutDir`：通过。
- `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18086"`：启动成功。
- `curl http://127.0.0.1:18086/api/health`：返回 `ok`。
- `curl http://127.0.0.1:18086/api/connection/db-types`：返回 PostgreSQL/MySQL/Oracle/SQLServer，不再包含 `SQLITE`。
- `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6060 --strictPort`：启动成功。
- `curl -I http://127.0.0.1:6060/`：返回 `HTTP 200`。


### 2026-03-12 14:14:29

## 本次目标
- 根据更正要求恢复 `SQLITE` 配置，并将新增连接的默认数据库类型改为 `MYSQL`。

## 关键改动
- `apps/server/src/main/resources/jdbc-drivers.yml`
  - 恢复 `SQLITE` 配置，保持可选数据库类型完全来源于配置文件。
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - `defaultConnectionForm()` 默认 `dbType` 调整为 `MYSQL`。
  - `ensureConnectionFormDbType()` 在当前值无效时优先回退到配置中的 `MYSQL`，若配置里没有 `MYSQL` 再回退到第一个配置项。

## 验证结果
- `mvn -f apps/server/pom.xml clean package -DskipTests`：通过。
- `npm run type-check`：通过。
- `npm run build -- --emptyOutDir`：通过。
- `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18086"`：启动成功。
- `curl http://127.0.0.1:18086/api/health`：返回 `ok`。
- `curl http://127.0.0.1:18086/api/connection/db-types`：返回 PostgreSQL/MySQL/SQLite/Oracle/SQLServer，说明 SQLite 已恢复且仍来自配置。
- `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6060 --strictPort`：启动成功。
- `curl -I http://127.0.0.1:6060/`：返回 `HTTP 200`。
