package com.sqlcopilot.studio.dto.sql;

import lombok.Data;

import java.util.List;

/** SQL Explain 响应对象。 */
@Data
public class ExplainVO {

    /** Explain 结果行。 */
    private List<QueryRowVO> rows;

    /** Explain 摘要说明。 */
    private String summary;
}
