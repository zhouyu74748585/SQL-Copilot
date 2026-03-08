package com.sqlcopilot.studio.dto.knowledge;

import lombok.Data;

/** 知识向量重建响应对象。 */
@Data
public class KnowledgeVectorRebuildVO {

    /** 重建术语数量。 */
    private Integer termCount;

    /** 重建样例数量。 */
    private Integer exampleCount;

    /** 重建完成时间戳（毫秒）。 */
    private Long rebuiltAt;

    /** 执行结果说明。 */
    private String message;
}
