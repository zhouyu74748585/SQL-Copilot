package com.sqlcopilot.studio.service.llm;

import lombok.Data;

import java.time.Duration;

/** 统一 LLM 网关请求。 */
@Data
public class LlmGatewayRequest {

    /** 模型选项 ID。 */
    private String modelId;

    /** 兼容旧调用方的模型字段。 */
    private String legacyModelName;

    /** system prompt。 */
    private String systemPrompt;

    /** user prompt。 */
    private String userPrompt;

    /** 任务标签。 */
    private String taskLabel;

    /** 超时时间。 */
    private Duration timeout;

    /** 采样温度。 */
    private Double temperature;
}
