package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

import java.util.List;

/** Schema 概览响应对象。 */
@Data
public class SchemaOverviewVO {

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 总表数量。 */
    private Integer tableCount;

    /** 总字段数量。 */
    private Integer columnCount;

    /** 表摘要列表。 */
    private List<TableSummaryVO> tableSummaries;

    /** 表摘要对象。 */
    @Data
    public static class TableSummaryVO {

        /** 表名。 */
        private String tableName;

        /** 表备注。 */
        private String tableComment;

        /** 估算行数。 */
        private Long rowEstimate;

        /** 表大小（字节）。 */
        private Long tableSizeBytes;
    }
}
