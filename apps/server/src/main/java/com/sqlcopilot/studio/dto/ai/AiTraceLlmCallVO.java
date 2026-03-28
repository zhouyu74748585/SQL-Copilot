package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 阶段中的 LLM 调用详情。 */
@Data
public class AiTraceLlmCallVO {

    /** 请求模型 ID。 */
    private String modelId;

    /** 实际 provider 类型。 */
    private String providerType;

    /** provider 展示名称。 */
    private String providerName;

    /** 实际调用模型名。 */
    private String actualModel;

    /** LLM system prompt。 */
    private String systemPrompt;

    /** LLM user prompt。 */
    private String userPrompt;

    /** LLM 完整输出。 */
    private String fullOutput;

    /** 模型原始思考内容。 */
    private String thinkingContent;

    /** provider 请求 ID。 */
    private String providerRequestId;

    /** 当前调用是否使用流式。 */
    private Boolean streaming;

    /** 输入 token。 */
    private Integer promptTokens;

    /** 输出 token。 */
    private Integer completionTokens;

    /** 总 token。 */
    private Integer totalTokens;

    /** Prompt 预算快照。 */
    private PromptBudgetVO promptBudget;
}
