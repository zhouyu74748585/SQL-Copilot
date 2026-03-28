package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 文本响应对象。 */
@Data
public class AiTextResponseVO {

    /** AI 返回的文本内容。 */
    private String content;

    /** 处理过程说明。 */
    private String reasoning;

    /** 当前是否触发降级策略。 */
    private Boolean fallbackUsed;

    /** 粗略输入 token。 */
    private Integer promptTokens;

    /** 粗略输出 token。 */
    private Integer completionTokens;

    /** 粗略总 token。 */
    private Integer totalTokens;

    /** 本轮历史内容 token。 */
    private Integer turnContentTokens;

    /** 本次请求输入 token。 */
    private Integer requestPromptTokens;

    /** 本次请求输出 token。 */
    private Integer requestCompletionTokens;

    /** 本次请求总 token。 */
    private Integer requestTotalTokens;

    /** Prompt 预算快照。 */
    private PromptBudgetVO promptBudget;

    private AiTraceVO trace;
}
