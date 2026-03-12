package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

/**
 * 表复制响应对象。
 */
@Data
public class TableCopyVO {

    /** 是否成功受理或完成。 */
    private boolean success;

    /** 响应消息。 */
    private String message;

    /** 是否为异步任务。 */
    private boolean async;

    /** 异步任务 ID。 */
    private String taskId;

    /** 复制模式。 */
    private TableCopyMode copyMode;

    /** 目标连接主键 ID。 */
    private Long targetConnectionId;

    /** 目标数据库名称。 */
    private String targetDatabaseName;

    /** 目标表名。 */
    private String targetTableName;
}
