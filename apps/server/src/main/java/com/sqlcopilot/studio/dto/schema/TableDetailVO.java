package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

import java.util.List;

/** 表结构详情响应对象。 */
@Data
public class TableDetailVO {

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 表名。 */
    private String tableName;

    /** 字段详情列表。 */
    private List<ColumnDetailVO> columns;

    /** 字段详情对象。 */
    @Data
    public static class ColumnDetailVO {

        /** 字段名。 */
        private String columnName;

        /** 字段类型。 */
        private String dataType;

        /** 字段长度。 */
        private Integer columnSize;

        /** 小数位数。 */
        private Integer decimalDigits;

        /** 默认值。 */
        private String defaultValue;

        /** 是否自增。 */
        private Boolean autoIncrement;

        /** 是否可空。 */
        private Boolean nullable;

        /** 字段备注。 */
        private String columnComment;

        /** 是否命中索引。 */
        private Boolean indexed;

        /** 是否主键。 */
        private Boolean primaryKey;
    }
}
