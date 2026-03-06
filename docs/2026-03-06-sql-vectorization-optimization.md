# 2026-03-06 SQL 查询向量化优化记录

## 背景
- 目标：优化 SQL 历史向量化样本质量，提升后续自然语言 RAG 检索命中率。
- 问题：历史 SQL 向量文本主要依赖原始 SQL 片段，缺少归一化与关键语义标签，在语义召回场景下命中不稳定。

## 本次修改

### 1) SQL 历史向量文档增强
文件：`apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagIngestionServiceImpl.java`

- 新增 SQL 归一化处理：
  - 将字符串字面量统一替换为 `<str>`。
  - 将数字字面量统一替换为 `<num>`。
  - 压缩空白并转为小写，降低同义 SQL 的向量离散度。
- 新增 SQL 关键词标签提取：
  - 提取 `select/update/delete/join/group by/order by/where/with/limit` 等关键词，作为结构语义标签。
- 将 `normalized_sql_text` 与 `sql_keyword_tags` 写入 SQL 历史向量 metadata。
- 将“SQL关键词/SQL归一化”追加到历史 SQL 的向量化文本中，强化自然语言到 SQL 语义的匹配桥接。

### 2) SQL 分片向量文档增强
文件：`apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagIngestionServiceImpl.java`

- 原先 SQL 分片仅对 `fragment_text` 直接向量化。
- 现改为分片上下文文档向量化，包含：
  - 数据库名、分片类型、涉及表、涉及列、SQL关键词、分片 SQL、分片归一化。
- 将 `sql_keyword_tags` 透传到分片 metadata，便于后续检索侧做混合召回策略。

## 影响评估
- 对外接口无变更。
- 仅增强向量样本质量，不改变 SQL 执行逻辑。
- 兼容现有 collection 结构（新增 metadata 字段为向后兼容）。

## 验证
- 尝试执行后端 clean 构建：`mvn -f apps/server/pom.xml clean package -DskipTests`
- 当前环境构建失败，原因为依赖仓库 `https://repo.spring.io/milestone` 返回 `403`，导致 Spring Boot parent POM 无法解析（非本次代码逻辑错误）。
