package com.sqlcopilot.studio.dto.connection;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 更新数据库连接请求对象。 */
@Data
public class ConnectionUpdateReq extends ConnectionCreateReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long id;
}
