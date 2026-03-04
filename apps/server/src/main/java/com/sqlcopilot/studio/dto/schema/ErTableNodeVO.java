package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

import java.util.List;

@Data
public class ErTableNodeVO {

    private String tableName;

    private String tableComment;

    private List<ErColumnNodeVO> columns;
}

