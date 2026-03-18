package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SchemaDatabaseCreateReq {

    @NotNull
    private Long connectionId;

    @NotBlank
    private String targetDatabaseName;
}
