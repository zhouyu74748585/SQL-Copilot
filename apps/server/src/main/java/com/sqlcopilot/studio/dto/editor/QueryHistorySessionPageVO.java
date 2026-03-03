package com.sqlcopilot.studio.dto.editor;

import lombok.Data;

import java.util.List;

/** 会话历史分页响应对象。 */
@Data
public class QueryHistorySessionPageVO {

    /** 当前页码（从 1 开始）。 */
    private Integer pageNo;

    /** 每页条数。 */
    private Integer pageSize;

    /** 总会话数。 */
    private Long total;

    /** 是否还有下一页。 */
    private Boolean hasMore;

    /** 当前页会话摘要。 */
    private List<QueryHistorySessionVO> items;
}
