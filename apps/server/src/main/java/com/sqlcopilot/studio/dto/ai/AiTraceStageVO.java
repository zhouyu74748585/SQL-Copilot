package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

import java.util.List;

/** AI 阶段详情。 */
@Data
public class AiTraceStageVO {

    /** 阶段编码。 */
    private String stageCode;

    /** 阶段标题。 */
    private String stageLabel;

    /** 阶段类型。 */
    private String stageType;

    /** 阶段状态。 */
    private String status;

    /** 阶段耗时毫秒。 */
    private Long durationMs;

    /** 阶段输入字段。 */
    private List<AiTraceFieldVO> inputFields;

    /** 阶段输出字段。 */
    private List<AiTraceFieldVO> outputFields;

    /** 阶段中的 LLM 调用详情。 */
    private AiTraceLlmCallVO llmCall;
}
