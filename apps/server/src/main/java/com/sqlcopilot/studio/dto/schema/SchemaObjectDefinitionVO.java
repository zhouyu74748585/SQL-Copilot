package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/**
 * 对象定义查询响应。
 */
@Data
public class SchemaObjectDefinitionVO {

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 对象类型（views/functions）。 */
    private String objectType;

    /** 对象名称。 */
    private String objectName;

    /** 完整定义 SQL。 */
    private String definitionSql;
}
