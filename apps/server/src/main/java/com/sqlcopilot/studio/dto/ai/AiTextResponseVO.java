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
}
