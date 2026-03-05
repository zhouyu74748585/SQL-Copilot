package com.sqlcopilot.studio.entity;

import lombok.Data;

/** ER 图快照实体。 */
@Data
public class ErGraphSnapshotEntity {

    /** 快照主键 ID。 */
    private Long id;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 快照名称。 */
    private String snapshotName;

    /** 选中表名列表 JSON。 */
    private String selectedTablesJson;

    /** 模型标识。 */
    private String modelName;

    /** 布局模式。 */
    private String layoutMode;

    /** AI 关系置信度阈值。 */
    private Double aiConfidenceThreshold;

    /** 是否包含 AI 推断（1=是，0=否）。 */
    private Integer includeAiInference;

    /** ER 图结果 JSON。 */
    private String graphJson;

    /** 创建时间戳（毫秒）。 */
    private Long createdAt;

    /** 更新时间戳（毫秒）。 */
    private Long updatedAt;
}
