package com.sqlcopilot.studio.dto.memory;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** 历史 SQL 记忆提升请求对象。 */
@Data
public class MemoryHistoryPromoteReq {

    /** 待提升的查询历史主键 ID 列表。 */
    @NotEmpty
    private List<Long> historyIds;

    /** 可选的自定义标题。 */
    private String title;
}
