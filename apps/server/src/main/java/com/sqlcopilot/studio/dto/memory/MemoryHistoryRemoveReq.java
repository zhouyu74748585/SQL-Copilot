package com.sqlcopilot.studio.dto.memory;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除历史 SQL 记忆请求对象。 */
@Data
public class MemoryHistoryRemoveReq {

    /** 查询历史主键 ID。 */
    @NotNull
    private Long historyId;
}
