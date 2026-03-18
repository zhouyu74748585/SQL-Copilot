package com.sqlcopilot.studio.dto.connection;

import lombok.Data;

/** 连接分组响应对象。 */
@Data
public class ConnectionGroupVO {

    /** 分组 ID。 */
    private Long id;

    /** 分组名称。 */
    private String name;

    /** 排序值。 */
    private Integer sortOrder;

    /** 分组下连接数量。 */
    private Integer connectionCount;

    /** 是否默认分组。 */
    private Boolean defaultGroup;
}
