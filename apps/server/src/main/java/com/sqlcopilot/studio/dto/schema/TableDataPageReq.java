package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表数据分页查询请求。
 */
@Data
public class TableDataPageReq {

    /** 连接主键 ID。 */
    @NotNull(message = "连接ID不能为空")
    private Long connectionId;

    /** 数据库名称。 */
    @NotBlank(message = "数据库名称不能为空")
    private String databaseName;

    /** 表名。 */
    @NotBlank(message = "表名不能为空")
    private String tableName;

    /** 对象类型（tables/views）。 */
    private String objectType;

    /** 页码（从 1 开始）。 */
    private Integer pageNo;

    /** 每页条数。 */
    private Integer pageSize;

    /** 过滤条件列表（AND 关系）。 */
    @Valid
    private List<FilterItem> filters = new ArrayList<>();

    /** 排序条件列表（按顺序生效）。 */
    @Valid
    private List<SortItem> sorts = new ArrayList<>();

    /**
     * 单个过滤条件。
     */
    @Data
    public static class FilterItem {

        /** 过滤字段名。 */
        @NotBlank(message = "过滤字段不能为空")
        private String columnName;

        /** 操作符（EQ/NE/GT/GTE/LT/LTE/LIKE/IS_NULL/IS_NOT_NULL）。 */
        @NotBlank(message = "过滤操作符不能为空")
        private String operator;

        /** 过滤值（IS_NULL/IS_NOT_NULL 可为空）。 */
        private String value;
    }

    /**
     * 单个排序条件。
     */
    @Data
    public static class SortItem {

        /** 排序字段名。 */
        @NotBlank(message = "排序字段不能为空")
        private String columnName;

        /** 排序方向（ASC/DESC）。 */
        @NotBlank(message = "排序方向不能为空")
        private String direction;
    }
}
