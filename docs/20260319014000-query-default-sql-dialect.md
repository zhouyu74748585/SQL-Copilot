# 主题：query-default-sql-dialect

## 记录

### 2026-03-19 01:40:00

## 本轮变更
- 修正对象浏览页点击“SQL查询”后进入查询页的默认 SQL 模板，不再统一使用 `LIMIT 100`。
- 新增统一的对象查询 SQL 生成逻辑，按连接数据库类型输出对应方言：
  - SQL Server：`SELECT TOP 100 * FROM ...`
  - Oracle：`SELECT * FROM ... FETCH FIRST 100 ROWS ONLY`
  - MySQL / PostgreSQL / SQLite：`SELECT * FROM ... LIMIT 100`
- 对对象名增加按段引用处理，避免带 schema 或特殊字符时直接拼接 SQL。

## 涉及文件
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`

## 验证
- `npm run -w @sqlcopilot/desktop type-check`：通过。
- `npm run -w @sqlcopilot/desktop build`：通过。
- 待继续执行 clean 后的启动验证。
