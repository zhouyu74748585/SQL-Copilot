package com.sqlcopilot.studio.dto.connection;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 移动连接到分组请求对象。 */
@Data
public class ConnectionGroupMoveReq {

    /** 连接 ID。 */
    @NotNull
    private Long connectionId;

    /** 目标分组 ID。 */
    @NotNull
    private Long targetGroupId;
}
