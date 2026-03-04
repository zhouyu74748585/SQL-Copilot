package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

@Data
public class ErRelationVO {

    private String sourceTable;

    private String sourceColumn;

    private String targetTable;

    private String targetColumn;

    private String relationType;

    private Double confidence;

    private String reason;
}

