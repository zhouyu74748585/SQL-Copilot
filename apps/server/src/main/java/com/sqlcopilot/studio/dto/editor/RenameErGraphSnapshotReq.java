package com.sqlcopilot.studio.dto.editor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 重命名 ER 图快照请求对象。 */
@Data
public class RenameErGraphSnapshotReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 快照主键 ID。 */
    @NotNull
    private Long snapshotId;

    /** 新的快照名称。 */
    @NotBlank
    private String snapshotName;
}

