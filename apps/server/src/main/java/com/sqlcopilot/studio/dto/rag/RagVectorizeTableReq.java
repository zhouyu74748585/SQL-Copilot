package com.sqlcopilot.studio.dto.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 单表手动向量化请求对象。 */
@Data
public class RagVectorizeTableReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 目标数据库名称。 */
    @NotBlank
    private String databaseName;

    /** 目标表名。 */
    @NotBlank
    private String tableName;
}
