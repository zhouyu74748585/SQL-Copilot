package com.sqlcopilot.studio.dto.schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表数据提交请求。
 */
@Data
public class TableDataCommitReq {

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

    /** 待新增行列表。 */
    @Valid
    private List<InsertRowReq> inserts = new ArrayList<>();

    /** 待更新行列表。 */
    @Valid
    private List<UpdateRowReq> updates = new ArrayList<>();

    /** 待删除行列表。 */
    @Valid
    private List<DeleteRowReq> deletes = new ArrayList<>();

    /**
     * 单元格值。
     */
    @Data
    public static class CellValueReq {

        /** 列名。 */
        @NotBlank(message = "列名不能为空")
        private String columnName;

        /** 单元格值（null 表示空值）。 */
        private String cellValue;
    }

    /**
     * 新增行请求。
     */
    @Data
    public static class InsertRowReq {

        /** 行内字段值列表。 */
        @Valid
        @NotEmpty(message = "新增数据不能为空")
        private List<CellValueReq> cells = new ArrayList<>();
    }

    /**
     * 更新行请求。
     */
    @Data
    public static class UpdateRowReq {

        /** 主键值列表（联合主键需传完整）。 */
        @Valid
        @NotEmpty(message = "更新主键值不能为空")
        private List<CellValueReq> primaryKeyValues = new ArrayList<>();

        /** 待更新字段值列表（不允许包含主键列）。 */
        @Valid
        @NotEmpty(message = "更新字段不能为空")
        private List<CellValueReq> cells = new ArrayList<>();
    }

    /**
     * 删除行请求。
     */
    @Data
    public static class DeleteRowReq {

        /** 主键值列表（联合主键需传完整）。 */
        @Valid
        @NotEmpty(message = "删除主键值不能为空")
        private List<CellValueReq> primaryKeyValues = new ArrayList<>();
    }
}
