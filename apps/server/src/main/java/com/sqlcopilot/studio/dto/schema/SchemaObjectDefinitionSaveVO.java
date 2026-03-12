package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/**
 * 对象定义保存响应。
 */
@Data
public class SchemaObjectDefinitionSaveVO {

    /** 是否保存成功。 */
    private boolean success;

    /** 响应消息。 */
    private String message;

    /** 数据库名称。 */
    private String databaseName;

    /** 对象类型（views/functions）。 */
    private String objectType;

    /** 对象名称。 */
    private String objectName;

    /** 保存后的完整定义 SQL。 */
    private String definitionSql;
}
