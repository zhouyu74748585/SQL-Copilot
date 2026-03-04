package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

import java.util.List;

@Data
public class ErGraphVO {

    private Long connectionId;

    private String databaseName;

    private List<ErTableNodeVO> tables;

    private List<ErRelationVO> foreignKeyRelations;

    private List<ErRelationVO> aiRelations;

    private ErAiInferenceStatusVO aiInference;

    private Long generatedAt;
}

