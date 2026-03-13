package com.sqlcopilot.studio.dto.sql;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** SQL 中断请求对象。 */
@Data
public class SqlInterruptReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 查询页签会话 ID。 */
    @NotBlank
    private String sessionId;
}
