package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** Prompt 预算快照。 */
@Data
public class PromptBudgetVO {

    /** 模型上下文窗口上限。 */
    private Integer contextWindowTokens;

    /** 预留输出 token。 */
    private Integer completionReserveTokens;

    /** 额外安全冗余 token。 */
    private Integer safetyMarginTokens;

    /** 本次 prompt 可用预算。 */
    private Integer promptBudgetTokens;

    /** 本次最终 prompt 实际消耗。 */
    private Integer promptTokens;

    /** 会话原文窗口已使用 token。 */
    private Integer memoryWindowUsedTokens;

    /** 会话原文窗口预算。 */
    private Integer memoryWindowBudgetTokens;

    /** 使用的 tokenizer 类型。 */
    private String tokenizerType;

    /** 当前预算是否仍超限。 */
    private Boolean overBudget;
}
