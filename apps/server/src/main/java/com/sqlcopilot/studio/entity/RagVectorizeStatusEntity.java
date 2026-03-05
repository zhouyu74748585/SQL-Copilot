package com.sqlcopilot.studio.entity;

import lombok.Data;

/** 数据库向量化状态实体。 */
@Data
public class RagVectorizeStatusEntity {

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 状态值。 */
    private String status;

    /** 状态说明。 */
    private String message;

    /** 最近更新时间（毫秒时间戳）。 */
    private Long updatedAt;

    /** 最近一次整库全量向量化耗时（毫秒）。 */
    private Long lastFullVectorizeDurationMs;

    /** 最近一次整库全量向量化执行引擎。 */
    private String lastFullVectorizeProvider;
}
