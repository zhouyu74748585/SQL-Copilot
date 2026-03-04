package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

@Data
public class ErColumnNodeVO {

    private String columnName;

    private String dataType;

    private Boolean primaryKey;

    private Boolean indexed;

    private Boolean nullable;
}

