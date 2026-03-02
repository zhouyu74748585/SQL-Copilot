package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class SchemaColumnCacheEntity {
    private Long id;
    private Long connectionId;
    private String databaseName;
    private String tableName;
    private String columnName;
    private String dataType;
    private Integer columnSize;
    private Integer decimalDigits;
    private String columnDefault;
    private Integer autoIncrementFlag;
    private Integer nullableFlag;
    private String columnComment;
    private Integer indexedFlag;
    private Integer primaryKeyFlag;
    private Long updatedAt;
}
