package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class KnowledgeExampleSqlEntity {
    private Long id;
    private String scope;
    private Long connectionId;
    private String databaseName;
    private String sqlText;
    private String description;
    private String termIdsJson;
    private Long createdAt;
    private Long updatedAt;
}
