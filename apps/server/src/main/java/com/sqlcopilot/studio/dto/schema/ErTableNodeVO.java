package com.sqlcopilot.studio.dto.schema;

import java.util.List;
import lombok.Data;

@Data
public class ErTableNodeVO {

    private String tableName;

    private String tableComment;

    private List<ErColumnNodeVO> columns;
}

