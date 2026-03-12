package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/** 删除对象响应对象。 */
@Data
public class SchemaObjectDropVO {

    /** 是否成功。 */
    private boolean success;

    /** 响应消息。 */
    private String message;

    /** 数据库名称。 */
    private String databaseName;

    /** 对象类型。 */
    private String objectType;

    /** 对象名称。 */
    private String objectName;

    /** 执行 SQL。 */
    private String executedSql;
}
