package com.sqlcopilot.studio.dto.sql;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** SQL 执行请求对象。 */
@Data
public class SqlExecuteReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 会话 ID。 */
    @NotBlank
    private String sessionId;

    /** 待执行 SQL。 */
    @NotBlank
    private String sqlText;

    /** 目标数据库名称（可选，未传时使用连接默认库）。 */
    private String databaseName;

    /** 记忆理解开关：开启后执行成功 SQL 才提交向量化。 */
    private Boolean memoryEnabled;

    /** 查询结果最大返回行数，范围 1-5000，未传时使用系统默认值。 */
    @Min(1)
    @Max(5000)
    private Integer maxRows;

    /** 风险确认令牌，当前风险策略要求确认时必填。 */
    private String riskAckToken;

    /** 操作人。 */
    private String operatorName;
}
