package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SchemaSchemaCreateReq {

    @NotNull
    private Long connectionId;

    @NotBlank
    private String databaseName;

    @NotBlank
    private String targetNamespaceName;
}
