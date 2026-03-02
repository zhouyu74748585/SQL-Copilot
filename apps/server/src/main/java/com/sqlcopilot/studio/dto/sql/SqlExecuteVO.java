package com.sqlcopilot.studio.dto.sql;

import lombok.Data;

import java.util.List;

/** SQL 执行响应对象。 */
@Data
public class SqlExecuteVO {

    /** 执行是否成功。 */
    private Boolean success;

    /** 影响行数。 */
    private Integer affectedRows;

    /** 执行耗时（毫秒）。 */
    private Long executionMs;

    /** 结果列定义。 */
    private List<ColumnMetaVO> columns;

    /** 结果行列表。 */
    private List<QueryRowVO> rows;

    /** 结果说明。 */
    private String message;
}
