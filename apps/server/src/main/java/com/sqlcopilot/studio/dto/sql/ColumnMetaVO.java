package com.sqlcopilot.studio.dto.sql;

import lombok.Data;

/** 查询结果列定义对象。 */
@Data
public class ColumnMetaVO {

    /** 列名。 */
    private String columnName;

    /** 列类型。 */
    private String columnType;
}
