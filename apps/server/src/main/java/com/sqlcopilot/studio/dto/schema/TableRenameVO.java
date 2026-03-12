package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/**
 * 表重命名响应对象。
 */
@Data
public class TableRenameVO {

    /** 是否执行成功。 */
    private boolean success;

    /** 响应消息。 */
    private String message;

    /** 数据库名称。 */
    private String databaseName;

    /** 原表名。 */
    private String sourceTableName;

    /** 新表名。 */
    private String targetTableName;
}
