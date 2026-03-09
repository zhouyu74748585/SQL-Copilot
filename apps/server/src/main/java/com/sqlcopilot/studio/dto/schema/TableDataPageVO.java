package com.sqlcopilot.studio.dto.schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表数据分页查询响应。
 */
@Data
public class TableDataPageVO {

    /** 表名。 */
    private String tableName;

    /** 是否允许编辑。 */
    private boolean editable;

    /** 只读原因（editable=false 时返回）。 */
    private String readOnlyReason;

    /** 列定义。 */
    private List<ColumnVO> columns = new ArrayList<>();

    /** 主键列名列表（支持联合主键）。 */
    private List<String> primaryKeyColumns = new ArrayList<>();

    /** 当前页数据行。 */
    private List<RowVO> rows = new ArrayList<>();

    /** 页码（从 1 开始）。 */
    private Integer pageNo;

    /** 每页条数。 */
    private Integer pageSize;

    /** 是否存在下一页。 */
    private Boolean hasNext;

    /**
     * 列信息。
     */
    @Data
    public static class ColumnVO {

        /** 列名。 */
        private String columnName;

        /** 列类型。 */
        private String columnType;

        /** 列注释。 */
        private String columnComment;

        /** 是否可空。 */
        private Boolean nullable;

        /** 是否主键。 */
        private Boolean primaryKey;
    }

    /**
     * 行信息。
     */
    @Data
    public static class RowVO {

        /** 行唯一标识（仅用于前端渲染）。 */
        private String rowKey;

        /** 单元格列表。 */
        private List<CellVO> cells = new ArrayList<>();
    }

    /**
     * 单元格信息。
     */
    @Data
    public static class CellVO {

        /** 列名。 */
        private String columnName;

        /** 单元格值（统一字符串表示，null 保持为空）。 */
        private String cellValue;
    }
}
