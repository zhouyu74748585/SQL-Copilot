package com.sqlcopilot.studio.dto.connection;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除数据库连接请求对象。 */
@Data
public class ConnectionRemoveReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long id;
}
