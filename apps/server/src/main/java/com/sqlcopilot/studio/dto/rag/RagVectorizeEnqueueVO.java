package com.sqlcopilot.studio.dto.rag;

import lombok.Data;

/** RAG 重新向量化入队响应对象。 */
@Data
public class RagVectorizeEnqueueVO {

    /** 是否成功入队。 */
    private Boolean enqueued;

    /** 当前队列中的任务数量（含执行中）。 */
    private Integer queueSize;

    /** 结果说明。 */
    private String message;
}
