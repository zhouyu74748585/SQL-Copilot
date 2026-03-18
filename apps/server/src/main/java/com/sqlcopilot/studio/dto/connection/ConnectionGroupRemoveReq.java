package com.sqlcopilot.studio.dto.connection;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除连接分组请求对象。 */
@Data
public class ConnectionGroupRemoveReq {

    /** 分组 ID。 */
    @NotNull
    private Long groupId;
}
