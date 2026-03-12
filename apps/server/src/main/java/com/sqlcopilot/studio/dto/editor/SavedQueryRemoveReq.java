package com.sqlcopilot.studio.dto.editor;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除保存查询请求对象。 */
@Data
public class SavedQueryRemoveReq {

    /** 记录主键 ID。 */
    @NotNull
    private Long id;

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;
}
