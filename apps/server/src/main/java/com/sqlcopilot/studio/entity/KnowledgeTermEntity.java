package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class KnowledgeTermEntity {
    private Long id;
    private String scope;
    private Long connectionId;
    private String databaseName;
    private String term;
    private String description;
    private String aliasesJson;
    private String metricExpression;
    private String relatedTablesJson;
    private String relatedColumnsJson;
    private String termType;
    private Long createdAt;
    private Long updatedAt;
}
