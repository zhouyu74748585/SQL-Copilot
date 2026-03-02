package com.sqlcopilot.studio.dto.sql;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** SQL Explain 请求对象。 */
@Data
public class ExplainReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 待分析 SQL。 */
    @NotBlank
    private String sqlText;
}
