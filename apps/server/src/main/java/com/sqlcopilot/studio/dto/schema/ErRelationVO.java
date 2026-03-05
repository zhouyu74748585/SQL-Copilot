package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

@Data
public class ErRelationVO {

    /** 源表名。 */
    private String sourceTable;

    /** 源字段名。 */
    private String sourceColumn;

    /** 目标表名。 */
    private String targetTable;

    /** 目标字段名。 */
    private String targetColumn;

    /** 关系类型（如 FK / AI_INFERRED）。 */
    private String relationType;

    /** 关系方向（SOURCE_TO_TARGET / TARGET_TO_SOURCE / BIDIRECTIONAL）。 */
    private String relationDirection;

    /** 置信度（0~1）。 */
    private Double confidence;

    /** 推断理由。 */
    private String reason;
}
