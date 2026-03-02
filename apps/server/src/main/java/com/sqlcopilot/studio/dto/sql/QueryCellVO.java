package com.sqlcopilot.studio.dto.sql;

import lombok.Data;

/** 查询结果单元格对象。 */
@Data
public class QueryCellVO {

    /** 列名。 */
    private String columnName;

    /** 单元格值字符串。 */
    private String cellValue;
}
