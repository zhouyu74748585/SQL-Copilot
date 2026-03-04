package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

import java.util.List;

@Data
public class ErAiInferenceReq {

    private Long connectionId;

    private String databaseName;

    private String modelName;

    private Double confidenceThreshold;

    private List<ErTableNodeVO> tables;

    private List<ErRelationVO> foreignKeyRelations;
}

