package com.sqlcopilot.studio.dto.editor;

import com.sqlcopilot.studio.dto.ai.AiTraceVO;
import com.sqlcopilot.studio.dto.ai.ChartConfigVO;
import com.sqlcopilot.studio.dto.ai.PromptBudgetVO;
import lombok.Data;

/** 查询历史响应对象。 */
@Data
public class QueryHistoryVO {

    /** 历史记录 ID。 */
    private Long id;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 会话 ID。 */
    private String sessionId;

    /** 自然语言输入。 */
    private String promptText;

    /** SQL 文本。 */
    private String sqlText;

    /** 历史类型：CHAT/EXECUTE。 */
    private String historyType;

    /** 动作类型。 */
    private String actionType;

    /** 助手文本内容。 */
    private String assistantContent;

    /** 数据库名称。 */
    private String databaseName;

    /** 图表配置。 */
    private ChartConfigVO chartConfig;

    /** 图表缓存图片键。 */
    private String chartImageCacheKey;

    /** 结构化上下文（JSON）。 */
    private String structuredContextJson;

    private String traceJson;

    private AiTraceVO trace;

    /** 粗略 token 估算值。 */
    private Integer tokenEstimate;

    /** 单条历史内容 token。 */
    private Integer turnContentTokens;

    /** 本次请求输入 token。 */
    private Integer requestPromptTokens;

    /** 本次请求输出 token。 */
    private Integer requestCompletionTokens;

    /** 本次请求总 token。 */
    private Integer requestTotalTokens;

    /** token 估算来源。 */
    private String tokenEstimateSource;

    /** token 估算版本。 */
    private Integer tokenEstimateVersion;

    /** token 估算范围。 */
    private String tokenEstimateScope;

    /** prompt 预算快照。 */
    private PromptBudgetVO promptBudget;

    /** 是否开启会话记忆。 */
    private Boolean memoryEnabled;

    /** 执行耗时（毫秒）。 */
    private Long executionMs;

    /** 是否执行成功。 */
    private Boolean success;

    /** 创建时间戳（毫秒）。 */
    private Long createdAt;
}
