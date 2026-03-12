package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除库/命名空间请求对象。 */
@Data
public class SchemaNamespaceDropReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 待删除名称。 */
    @NotBlank
    private String sourceNamespaceName;
}
