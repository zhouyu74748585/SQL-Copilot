package com.sqlcopilot.studio.dto.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 向量化中断请求对象。 */
@Data
public class RagVectorizeInterruptReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 数据库名称。 */
    @NotBlank
    private String databaseName;
}
