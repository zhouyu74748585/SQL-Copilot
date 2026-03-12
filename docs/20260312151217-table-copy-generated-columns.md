# 主题：table-copy-generated-columns

## 记录

### 2026-03-12 15:12:17

## 2026-03-12 MySQL 生成列复制修复

### 本次目标
- 修复同连接快速复制 MySQL 含生成列表时失败，报错 `The value specified for generated column ... is not allowed`。

### 关键改动
- 后端 `SchemaServiceImpl` 复用 MySQL `information_schema.columns.EXTRA`，为 `TableDetailVO.ColumnDetailVO` 补充 `generated` 标记。
- 后端 `TableCopyServiceImpl` 在复制数据时只选择可写普通列，自动排除生成列，避免对生成列显式 `INSERT`。
- 同连接 `STRUCTURE_AND_DATA` 快速复制若检测到生成列，自动跳过 `INSERT INTO target SELECT * FROM source` fast path，回退到“保留建表 DDL + 显式列清单复制”的安全路径。
- 当源表不存在可写普通列时，返回明确错误，避免执行无效复制 SQL。

### 验证结果
- 后端打包：`mvn -f apps/server/pom.xml clean package` 未通过；失败原因为现有测试 `AiServiceImplAstValidationTest`、`OnnxLocalRerankServiceImplTest` 断言失败，和本次表复制改动无直接关系。
- 后端可交付构建：`mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
- 前端类型检查：`npm run type-check` 通过。
- 前端 clean 构建：`npm run build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18087"` 成功，`http://127.0.0.1:18087/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6061 --strictPort` 成功，`http://127.0.0.1:6061/` 返回 `HTTP/1.1 200 OK`。
