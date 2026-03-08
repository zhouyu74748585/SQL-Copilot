package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 阶段字段展示项。 */
@Data
public class AiTraceFieldVO {

    /** 字段编码。 */
    private String fieldCode;

    /** 字段标题。 */
    private String fieldLabel;

    /** 字段文本值。 */
    private String fieldValue;
}
