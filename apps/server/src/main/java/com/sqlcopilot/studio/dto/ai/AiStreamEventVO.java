package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 流式统一事件对象。 */
@Data
public class AiStreamEventVO {

    /** 事件类型。 */
    private String eventType;

    /** 请求会话 ID。 */
    private String sessionId;

    /** 动作类型。 */
    private String actionType;

    /** 事件序号。 */
    private Long sequence;

    /** 事件时间戳。 */
    private Long timestamp;

    /** 流式增量。 */
    private AiStreamDeltaVO delta;

    /** 最终结果。 */
    private AiStreamFinalVO finalResult;

    /** 错误对象。 */
    private AiStreamErrorVO error;

    /** 自动模式意图结果。 */
    private AiStreamIntentVO intent;

    /** 阶段详情。 */
    private AiTraceStageVO stage;

    /** 当前追踪快照。 */
    private AiTraceVO trace;
}
