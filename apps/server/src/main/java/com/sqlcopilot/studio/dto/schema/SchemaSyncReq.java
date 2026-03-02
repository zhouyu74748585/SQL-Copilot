package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 同步数据库 Schema 请求对象。 */
@Data
public class SchemaSyncReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 指定同步的数据库名（可选，未传则使用连接默认库）。 */
    private String databaseName;
}
