package com.sqlcopilot.studio.dto.sql;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** SQL 风险评估请求对象。 */
@Data
public class RiskEvaluateReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 待评估 SQL。 */
    @NotBlank
    private String sqlText;
}
