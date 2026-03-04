package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ErGraphReq {

    @NotNull
    private Long connectionId;

    private String databaseName;

    @NotNull
    @Size(min = 1, max = 30)
    private List<@Size(min = 1, max = 128) String> tableNames;

    private String modelName;

    private Boolean includeAiInference;

    @Min(0)
    @Max(1)
    private Double aiConfidenceThreshold;
}

