package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 清空表请求对象。 */
@Data
public class TableTruncateReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 表名。 */
    @NotBlank
    private String tableName;
}
