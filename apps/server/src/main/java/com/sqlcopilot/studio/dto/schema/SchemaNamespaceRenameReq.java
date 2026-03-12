package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 重命名库/命名空间请求对象。 */
@Data
public class SchemaNamespaceRenameReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 原名称。 */
    @NotBlank
    private String sourceNamespaceName;

    /** 新名称。 */
    @NotBlank
    private String targetNamespaceName;
}
