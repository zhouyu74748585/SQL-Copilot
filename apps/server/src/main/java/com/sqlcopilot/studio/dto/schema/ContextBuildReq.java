package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 构建 Schema 上下文请求对象。 */
@Data
public class ContextBuildReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 用户自然语言问题。 */
    @NotBlank
    private String question;

    /** 上下文所属数据库名（可选）。 */
    private String databaseName;

    /** Token 预算，未传则使用默认值。 */
    private Integer tokenBudget;
}
