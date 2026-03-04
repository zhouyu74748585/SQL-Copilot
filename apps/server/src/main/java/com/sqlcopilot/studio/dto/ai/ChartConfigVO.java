package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

import java.util.List;

/** 图表配置响应对象。 */
@Data
public class ChartConfigVO {

    /** 图表类型：LINE/BAR/PIE/SCATTER/TREND。 */
    private String chartType;

    /** X 轴字段。 */
    private String xField;

    /** Y 轴字段列表（支持多字段）。 */
    private List<String> yFields;

    /** 饼图分类字段。 */
    private String categoryField;

    /** 饼图数值字段。 */
    private String valueField;

    /** 排序字段。 */
    private String sortField;

    /** 排序方向：NONE/ASC/DESC。 */
    private String sortDirection;

    /** 图表标题。 */
    private String title;

    /** 图表说明。 */
    private String description;
}
