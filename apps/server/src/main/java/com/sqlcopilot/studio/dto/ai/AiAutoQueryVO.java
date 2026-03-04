package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 自动模式响应对象。 */
@Data
public class AiAutoQueryVO {

    /** 意图类型：GENERATE_SQL|EXPLAIN_SQL|ANALYZE_SQL|GENERATE_CHART。 */
    private String intentType;

    /** 意图标签（用于前端展示）。 */
    private String intentLabel;

    /** 意图识别置信度（0-1）。 */
    private Double intentConfidence;

    /** 处理过程说明。 */
    private String reasoning;

    /** 当前是否触发降级策略。 */
    private Boolean fallbackUsed;

    /** 意图路由后返回的 SQL 文本（生成 SQL/图表方案时返回）。 */
    private String sqlText;

    /** 意图路由后返回的文本内容（解释/分析时返回）。 */
    private String content;

    /** 图表配置（图表方案时返回）。 */
    private ChartConfigVO chartConfig;

    /** 图表配置说明（图表方案时返回）。 */
    private String configSummary;
}
