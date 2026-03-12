package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 对象定义保存请求。
 */
@Data
public class SchemaObjectDefinitionSaveReq {

    /** 连接主键 ID。 */
    @NotNull(message = "连接ID不能为空")
    private Long connectionId;

    /** 数据库名称。 */
    @NotBlank(message = "数据库名称不能为空")
    private String databaseName;

    /** 对象类型（views/functions）。 */
    @NotBlank(message = "对象类型不能为空")
    private String objectType;

    /** 对象名称。 */
    @NotBlank(message = "对象名称不能为空")
    private String objectName;

    /** 完整定义 SQL。 */
    @NotBlank(message = "定义SQL不能为空")
    private String definitionSql;
}
