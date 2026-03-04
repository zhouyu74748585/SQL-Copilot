package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 生成图表方案响应对象。 */
@Data
public class AiGenerateChartVO {

    /** AI 生成的 SQL 文本。 */
    private String sqlText;

    /** AI 生成的图表配置。 */
    private ChartConfigVO chartConfig;

    /** 图表配置说明。 */
    private String configSummary;

    /** 生成理由说明。 */
    private String reasoning;

    /** 当前是否触发降级。 */
    private Boolean fallbackUsed;
}
