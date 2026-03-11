package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 表复制请求对象。
 */
@Data
public class TableCopyReq {

    /** 源连接主键 ID。 */
    @NotNull
    private Long sourceConnectionId;

    /** 源数据库名称，未传时使用连接默认库。 */
    private String sourceDatabaseName;

    /** 源表名。 */
    @NotBlank
    private String sourceTableName;

    /** 目标连接主键 ID。 */
    @NotNull
    private Long targetConnectionId;

    /** 目标数据库名称，未传时使用连接默认库。 */
    private String targetDatabaseName;

    /** 目标表名。 */
    @NotBlank
    private String targetTableName;

    /** 复制模式。 */
    @NotNull
    private TableCopyMode copyMode;
}
