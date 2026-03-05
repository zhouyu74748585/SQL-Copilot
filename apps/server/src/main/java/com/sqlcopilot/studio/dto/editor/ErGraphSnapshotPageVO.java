package com.sqlcopilot.studio.dto.editor;

import lombok.Data;

import java.util.List;

/** ER 图快照分页响应对象。 */
@Data
public class ErGraphSnapshotPageVO {

    /** 当前页码（从 1 开始）。 */
    private Integer pageNo;

    /** 每页条数。 */
    private Integer pageSize;

    /** 总记录数。 */
    private Long total;

    /** 是否存在下一页。 */
    private Boolean hasMore;

    /** 当前页快照摘要。 */
    private List<ErGraphSnapshotSummaryVO> items;
}
