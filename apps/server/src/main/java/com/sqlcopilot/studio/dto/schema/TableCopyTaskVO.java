package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/**
 * 表复制任务进度响应对象。
 */
@Data
public class TableCopyTaskVO {

    /** 任务 ID。 */
    private String taskId;

    /** 任务状态。 */
    private String status;

    /** 当前阶段。 */
    private String stage;

    /** 当前阶段说明。 */
    private String message;

    /** 进度百分比。 */
    private Integer progressPercent;

    /** 已复制行数。 */
    private Long copiedRows;

    /** 总行数。 */
    private Long totalRows;

    /** 源连接主键 ID。 */
    private Long sourceConnectionId;

    /** 源数据库名称。 */
    private String sourceDatabaseName;

    /** 源表名。 */
    private String sourceTableName;

    /** 目标连接主键 ID。 */
    private Long targetConnectionId;

    /** 目标数据库名称。 */
    private String targetDatabaseName;

    /** 目标表名。 */
    private String targetTableName;

    /** 复制模式。 */
    private TableCopyMode copyMode;

    /** 最近更新时间。 */
    private Long updatedAt;
}
