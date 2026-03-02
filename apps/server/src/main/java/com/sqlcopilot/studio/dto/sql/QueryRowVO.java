package com.sqlcopilot.studio.dto.sql;

import lombok.Data;

import java.util.List;

/** 查询结果行对象。 */
@Data
public class QueryRowVO {

    /** 单元格列表。 */
    private List<QueryCellVO> cells;
}
