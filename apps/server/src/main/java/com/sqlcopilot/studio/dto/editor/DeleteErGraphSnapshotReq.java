package com.sqlcopilot.studio.dto.editor;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除 ER 图快照请求对象。 */
@Data
public class DeleteErGraphSnapshotReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 快照主键 ID。 */
    @NotNull
    private Long snapshotId;
}

