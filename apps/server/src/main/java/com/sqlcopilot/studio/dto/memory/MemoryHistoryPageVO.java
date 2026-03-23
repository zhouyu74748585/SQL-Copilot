package com.sqlcopilot.studio.dto.memory;

import lombok.Data;

import java.util.List;

/** 历史 SQL 记忆分页响应对象。 */
@Data
public class MemoryHistoryPageVO {

    /** 当前页码（从 1 开始）。 */
    private Integer pageNo;

    /** 每页条数。 */
    private Integer pageSize;

    /** 总条数。 */
    private Long total;

    /** 是否还有下一页。 */
    private Boolean hasMore;

    /** 当前页数据。 */
    private List<MemoryHistoryVO> items;
}
