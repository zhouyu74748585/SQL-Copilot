package com.sqlcopilot.studio.dto.memory;

import lombok.Data;

/** 历史 SQL 记忆分页查询对象。 */
@Data
public class MemoryHistoryPageReq {

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** SQL、问题或语义摘要关键字。 */
    private String keyword;

    /** 当前页码（从 1 开始）。 */
    private Integer pageNo;

    /** 每页条数。 */
    private Integer pageSize;
}
