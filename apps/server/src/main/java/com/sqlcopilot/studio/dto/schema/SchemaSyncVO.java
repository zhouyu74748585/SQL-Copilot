package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/** 同步数据库 Schema 响应对象。 */
@Data
public class SchemaSyncVO {

    /** 是否同步成功。 */
    private Boolean success;

    /** 同步到的表数量。 */
    private Integer tableCount;

    /** 同步到的字段数量。 */
    private Integer columnCount;

    /** 同步结果说明。 */
    private String message;
}
