package com.sqlcopilot.studio.dto.memory;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除长期记忆请求对象。 */
@Data
public class MemoryEntryRemoveReq {

    /** 长期记忆主键 ID。 */
    @NotNull
    private Long id;
}
