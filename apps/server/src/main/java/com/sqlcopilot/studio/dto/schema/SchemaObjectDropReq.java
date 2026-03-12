package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除对象请求对象。 */
@Data
public class SchemaObjectDropReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 数据库名称。 */
    @NotBlank
    private String databaseName;

    /** 对象类型，仅支持视图/函数。 */
    @NotBlank
    private String objectType;

    /** 对象名称。 */
    @NotBlank
    private String objectName;
}
