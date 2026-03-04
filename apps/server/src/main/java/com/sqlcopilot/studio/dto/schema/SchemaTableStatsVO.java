package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

import java.util.List;

/** 表统计信息响应对象。 */
@Data
public class SchemaTableStatsVO {

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 是否仍在后台刷新统计。 */
    private Boolean refreshing;

    /** 最近一次统计完成时间（毫秒时间戳）。 */
    private Long updatedAt;

    /** 表统计列表。 */
    private List<TableStatVO> tableStats;

    /** 单表统计对象。 */
    @Data
    public static class TableStatVO {

        /** 表名。 */
        private String tableName;

        /** 估算行数。 */
        private Long rowEstimate;

        /** 表大小（字节）。 */
        private Long tableSizeBytes;
    }
}
