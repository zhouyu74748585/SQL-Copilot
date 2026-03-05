package com.sqlcopilot.studio.dto.rag;

import lombok.Data;

/** 向量化概要统计响应对象。 */
@Data
public class RagVectorizeOverviewVO {

    /** 数据库名称。 */
    private String databaseName;

    /** 向量化状态。 */
    private String status;

    /** 状态说明。 */
    private String message;

    /** 状态更新时间（毫秒时间戳）。 */
    private Long updatedAt;

    /** 总向量条数。 */
    private Long totalVectorCount;

    /** 表级向量条数。 */
    private Long schemaTableVectorCount;

    /** 字段级向量条数。 */
    private Long schemaColumnVectorCount;

    /** SQL 历史向量条数。 */
    private Long sqlHistoryVectorCount;

    /** SQL 片段向量条数。 */
    private Long sqlFragmentVectorCount;

    /** 向量维度。 */
    private Integer vectorDimension;

    /** 上次整库全量向量化耗时（毫秒）。 */
    private Long lastFullVectorizeDurationMs;

    /** 上次整库全量向量化执行引擎。 */
    private String lastFullVectorizeProvider;
}
