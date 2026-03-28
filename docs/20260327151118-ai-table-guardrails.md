# 主题：ai-table-guardrails

## 记录

### 2026-03-27 15:11:18

## 本次目标
- 调整 AI 对话相关提示词，明确要求生成/修复 SQL 时只能使用当前提示词中已确认的表，禁止猜表或造表。

## 关键改动
- 修改 `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/AiServiceImpl.java`。
- 收紧 `OPENAI_SYSTEM_PROMPT`，新增“仅允许使用当前提示词中已经明确提供、确认的表”的硬约束。
- 收紧 `GENERATE_CHART_SYSTEM_PROMPT`，避免图表 SQL 生成时自行猜表。
- 收紧 `REPAIR_SQL_SYSTEM_PROMPT`，避免修复 SQL 时擅自替换成未提供的表。
- 在 `buildProviderUserPrompt()` 中新增“表使用硬约束”段落，并显式列出 `ConversationGenerationContext.relatedTables()` 作为允许使用的表清单。

## 验证结果
- 后端打包：`mvn -f apps/server/pom.xml clean package -DskipTests -Dfile.encoding=UTF-8` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18101' -Dfile.encoding=UTF-8` 启动成功；`curl --noproxy '*' http://127.0.0.1:18101/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端 clean 构建：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 前端 preview 探活：尝试拉起 `4181/4182` 时发现端口已被现有 preview 占用；`curl -I http://127.0.0.1:4182` 返回 `HTTP/1.1 200 OK`，可确认当前前端预览可访问。

## 遗留项
- 当前仅通过提示词约束限制猜表；若后续仍需进一步兜底，可在 SQL 生成后增加“引用表必须命中当前 schema / 允许表清单”的程序化校验。
