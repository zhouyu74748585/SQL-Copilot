package com.sqlcopilot.studio.dto.ai;

import lombok.Data;

/** AI 流式错误对象。 */
@Data
public class AiStreamErrorVO {

    /** 错误码。 */
    private Integer code;

    /** 错误消息。 */
    private String message;
}
