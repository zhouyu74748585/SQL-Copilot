package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

import java.util.List;

/** AI 调用链路详情。 */
@Data
public class AiTraceVO {

    /** 阶段数量。 */
    private Integer stageCount;

    /** 总耗时毫秒。 */
    private Long totalDurationMs;

    /** 阶段列表。 */
    private List<AiTraceStageVO> stages;
}
