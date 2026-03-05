package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

@Data
public class ErColumnNodeVO {

    /** 字段名。 */
    private String columnName;

    /** 字段类型。 */
    private String dataType;

    /** 字段备注。 */
    private String columnComment;

    /** 是否主键。 */
    private Boolean primaryKey;

    /** 是否命中索引。 */
    private Boolean indexed;

    /** 是否允许为空。 */
    private Boolean nullable;
}
