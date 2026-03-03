package com.sqlcopilot.studio.dto.rag;

import lombok.Data;

/** 向量化中断响应对象。 */
@Data
public class RagVectorizeInterruptVO {

    /** 是否成功中断。 */
    private Boolean interrupted;

    /** 当前状态。 */
    private String status;

    /** 状态说明。 */
    private String message;

    /** 状态更新时间（毫秒时间戳）。 */
    private Long updatedAt;
}
