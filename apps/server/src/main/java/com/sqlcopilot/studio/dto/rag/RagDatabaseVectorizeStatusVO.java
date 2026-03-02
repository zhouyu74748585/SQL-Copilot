package com.sqlcopilot.studio.dto.rag;

import lombok.Data;

/** 数据库向量化状态响应对象。 */
@Data
public class RagDatabaseVectorizeStatusVO {

    /** 数据库名称。 */
    private String databaseName;

    /**
     * 向量化状态。
     * 可选值：NOT_VECTORIZED、PENDING、RUNNING、SUCCESS、FAILED。
     */
    private String status;

    /** 最近一次状态说明。 */
    private String message;

    /** 最近一次状态更新时间（毫秒时间戳）。 */
    private Long updatedAt;
}
