package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

@Data
public class ErAiInferenceStatusVO {

    private Boolean requested;

    private Boolean success;

    private String message;
}

