package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 流式增量片段。 */
@Data
public class AiStreamDeltaVO {

    /** 增量通道：thinking|output。 */
    private String channel;

    /** 本次新增片段。 */
    private String deltaText;

    /** 当前累计文本。 */
    private String accumulatedText;
}
