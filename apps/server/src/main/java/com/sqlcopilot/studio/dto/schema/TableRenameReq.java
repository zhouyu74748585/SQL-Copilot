package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 表重命名请求对象。
 */
@Data
public class TableRenameReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 数据库名称，未传时使用连接默认库。 */
    private String databaseName;

    /** 原表名。 */
    @NotBlank
    private String sourceTableName;

    /** 新表名。 */
    @NotBlank
    private String targetTableName;
}
