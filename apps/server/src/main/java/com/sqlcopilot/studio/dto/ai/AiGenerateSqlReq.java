package com.sqlcopilot.studio.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** AI 生成 SQL 请求对象。 */
@Data
public class AiGenerateSqlReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 会话 ID。 */
    @NotBlank
    private String sessionId;

    /** 用户自然语言需求。 */
    @NotBlank
    private String prompt;

    /** 当前查询目标数据库（可选，RAG 检索过滤使用）。 */
    private String databaseName;

    /** 用户提供的 SQL 片段（可选，用于补全、修复与语义检索）。 */
    private String sqlSnippet;

    /** 本次会话指定模型选项 ID（兼容旧版模型名透传）。 */
    private String modelName;
}
