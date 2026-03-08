package com.sqlcopilot.studio.service.llm;

import lombok.Data;

/** 统一 LLM 网关结果。 */
@Data
public class LlmGatewayResult {

    /** 路由命中的模型 ID。 */
    private String modelId;

    /** provider 类型。 */
    private String providerType;

    /** provider 名称。 */
    private String providerName;

    /** 实际调用模型名。 */
    private String actualModel;

    /** 请求 system prompt。 */
    private String systemPrompt;

    /** 请求 user prompt。 */
    private String userPrompt;

    /** 模型输出内容。 */
    private String content;

    /** 模型完整输出。 */
    private String fullOutput;

    /** 网关摘要说明。 */
    private String reasoning;

    /** token 使用量。 */
    private OpenAiTextClient.TokenUsage usage;
}
