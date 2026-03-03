package com.sqlcopilot.studio.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** AI 修复 SQL 请求对象。 */
@Data
public class AiRepairReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 会话 ID。 */
    @NotBlank
    private String sessionId;

    /** 待修复 SQL。 */
    @NotBlank
    private String sqlText;

    /** 执行错误信息。 */
    @NotBlank
    private String errorMessage;

    /** 当前查询目标数据库（可选，用于元数据和 RAG 上下文过滤）。 */
    private String databaseName;

    /** 本次会话指定模型选项 ID（可选）。 */
    private String modelName;
}
