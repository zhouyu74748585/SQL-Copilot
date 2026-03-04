package com.sqlcopilot.studio.dto.schema;

import java.util.List;
import lombok.Data;

@Data
public class ErAiInferenceReq {

    private Long connectionId;

    private String databaseName;

    private String modelName;

    private Double confidenceThreshold;

    private List<ErTableNodeVO> tables;

    private List<ErRelationVO> foreignKeyRelations;
}

