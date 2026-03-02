package com.sqlcopilot.studio.dto.rag;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** RAG 向量化状态查询请求对象。 */
@Data
public class RagVectorizeStatusQueryReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;
}
