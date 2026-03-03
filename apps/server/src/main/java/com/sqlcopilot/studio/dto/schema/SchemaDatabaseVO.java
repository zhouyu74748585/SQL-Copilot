package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/** 数据库列表项响应对象。 */
@Data
public class SchemaDatabaseVO {

    /** 数据库名称。 */
    private String databaseName;

    /**
     * 向量化状态。
     * 可选值：NOT_VECTORIZED、PENDING、RUNNING、SUCCESS、FAILED。
     */
    private String vectorizeStatus;

    /** 向量化状态说明。 */
    private String vectorizeMessage;

    /** 向量化状态更新时间（毫秒时间戳）。 */
    private Long vectorizeUpdatedAt;
}
