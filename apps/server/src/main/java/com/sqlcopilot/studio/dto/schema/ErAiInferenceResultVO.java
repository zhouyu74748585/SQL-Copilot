package com.sqlcopilot.studio.dto.schema;

import java.util.List;
import lombok.Data;

@Data
public class ErAiInferenceResultVO {

    private Boolean success;

    private String message;

    private List<ErRelationVO> relations;
}

