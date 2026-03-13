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
    private String questionText;
    private String questionVariantsJson;
    private String semanticSummary;
    private String normalizedSql;
    private String sqlTemplate;
    private String sqlAstJson;
    private String tableNamesJson;
    private String columnNamesJson;
    private String metricTagsJson;
    private String timeTagsJson;
    private Integer verifiedFlag;
    private Double qualityScore;
    private String sourceType;
    private String sqlOperationType;
    private Long createdAt;
    private Long updatedAt;
}
