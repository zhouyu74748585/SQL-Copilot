package com.sqlcopilot.studio.dto.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** RAG 重新向量化入队请求对象。 */
@Data
public class RagVectorizeEnqueueReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 目标数据库名称。 */
    @NotBlank
    private String databaseName;
}
