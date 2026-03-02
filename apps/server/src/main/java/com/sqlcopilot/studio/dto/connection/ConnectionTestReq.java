package com.sqlcopilot.studio.dto.connection;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 测试数据库连接请求对象。 */
@Data
public class ConnectionTestReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;
}
