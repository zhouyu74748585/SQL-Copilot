package com.sqlcopilot.studio.dto.memory;

import lombok.Data;

/** 长期记忆分页查询对象。 */
@Data
public class MemoryEntryPageReq {

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 记忆作用域，仅支持 CONNECTION / DATABASE。 */
    private String scope;

    /** 标题或摘要关键字。 */
    private String keyword;

    /** 当前页码（从 1 开始）。 */
    private Integer pageNo;

    /** 每页条数。 */
    private Integer pageSize;
}
