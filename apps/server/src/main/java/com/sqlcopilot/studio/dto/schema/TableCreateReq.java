package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class TableCreateReq {

    @NotNull(message = "连接ID不能为空")
    private Long connectionId;

    @NotBlank(message = "数据库名称不能为空")
    private String databaseName;

    @NotBlank(message = "表名不能为空")
    private String tableName;

    private String tableComment;

    @NotEmpty(message = "字段列表不能为空")
    private List<ColumnDefinition> columns;

    private List<IndexDefinition> indexes;

    private String ddl;

    @Data
    public static class ColumnDefinition {
        @NotBlank(message = "字段名不能为空")
        private String columnName;

        @NotBlank(message = "字段类型不能为空")
        private String dataType;

        private Integer columnSize;
        private Integer decimalDigits;
        private String defaultValue;
        private Boolean autoIncrement;
        private Boolean nullable;
        private String columnComment;
        private Boolean primaryKey;
        private Boolean indexed;
        private Boolean defaultCurrentTimestamp;
        private Boolean onUpdateCurrentTimestamp;
    }

    @Data
    public static class IndexDefinition {
        @NotBlank(message = "索引名不能为空")
        private String indexName;

        private Boolean unique;

        @NotEmpty(message = "索引字段不能为空")
        private List<String> columns;
    }
}
