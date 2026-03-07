package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

import java.util.List;

@Data
public class TableDetailVO {

    private Long connectionId;
    private String tableName;
    private String tableComment;
    private List<ColumnDetailVO> columns;
    private List<IndexDetailVO> indexes;

    @Data
    public static class ColumnDetailVO {
        private String columnName;
        private String dataType;
        private Integer columnSize;
        private Integer decimalDigits;
        private String defaultValue;
        private Boolean autoIncrement;
        private Boolean nullable;
        private String columnComment;
        private Boolean indexed;
        private Boolean primaryKey;
        private Boolean defaultCurrentTimestamp;
        private Boolean onUpdateCurrentTimestamp;
    }

    @Data
    public static class IndexDetailVO {
        private String indexName;
        private Boolean unique;
        private List<String> columns;
    }
}
