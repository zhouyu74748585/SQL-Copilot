package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class SchemaTableCacheEntity {
    private Long id;
    private Long connectionId;
    private String databaseName;
    private String tableName;
    private String tableComment;
    private Long rowEstimate;
    private Long tableSizeBytes;
    private Long updatedAt;
}
