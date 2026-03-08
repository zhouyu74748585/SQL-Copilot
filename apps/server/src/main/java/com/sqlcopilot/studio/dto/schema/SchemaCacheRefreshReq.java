package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 刷新 Schema 缓存请求对象。 */
@Data
public class SchemaCacheRefreshReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;
}
