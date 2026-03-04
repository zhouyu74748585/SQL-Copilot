package com.sqlcopilot.studio.dto.editor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除会话历史请求对象。 */
@Data
public class DeleteHistorySessionReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 会话 ID。 */
    @NotBlank
    private String sessionId;
}
