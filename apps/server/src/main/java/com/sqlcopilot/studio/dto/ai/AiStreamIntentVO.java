package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 自动模式意图识别事件对象。 */
@Data
public class AiStreamIntentVO {

    /** 意图类型。 */
    private String intentType;

    /** 意图标签。 */
    private String intentLabel;

    /** 意图置信度。 */
    private Double intentConfidence;

    /** 意图说明。 */
    private String reasoning;
}
