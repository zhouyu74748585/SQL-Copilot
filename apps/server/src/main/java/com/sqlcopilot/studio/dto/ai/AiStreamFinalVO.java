package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 流式最终结果。 */
@Data
public class AiStreamFinalVO {

    /** 动作类型。 */
    private String actionType;

    /** SQL 生成最终结果。 */
    private AiGenerateSqlVO generateSql;

    /** Auto 最终结果。 */
    private AiAutoQueryVO autoQuery;

    /** 文本解释/分析最终结果。 */
    private AiTextResponseVO textResponse;

    /** 图表方案最终结果。 */
    private AiGenerateChartVO generateChart;

    /** SQL 修复最终结果。 */
    private AiRepairVO repair;
}
