package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 新建库/命名空间请求对象。 */
@Data
public class SchemaNamespaceCreateReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 新建名称。 */
    @NotBlank
    private String targetNamespaceName;
}
