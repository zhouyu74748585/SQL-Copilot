package com.sqlcopilot.studio.service.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** SQL 结构特征元数据。 */
@Data
@AllArgsConstructor
public class SqlFeatureMeta {

    /** SQL 涉及的表名集合。 */
    private List<String> tables;

    /** SQL 涉及的字段名集合。 */
    private List<String> columns;

    /** SQL JOIN 次数。 */
    private Integer joinCount;

    /** 是否包含 CTE。 */
    private Boolean hasCte;
}
