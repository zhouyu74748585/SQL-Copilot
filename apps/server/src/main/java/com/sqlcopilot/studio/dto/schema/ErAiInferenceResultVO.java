package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

import java.util.List;

@Data
public class ErAiInferenceResultVO {

    private Boolean success;

    private String message;

    private List<ErRelationVO> relations;
}

