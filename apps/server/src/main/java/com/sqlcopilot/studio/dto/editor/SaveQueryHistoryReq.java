package com.sqlcopilot.studio.dto.editor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 保存查询历史请求对象。 */
@Data
public class SaveQueryHistoryReq {

    /** 连接主键 ID。 */
    @NotNull
    private Long connectionId;

    /** 会话 ID。 */
    @NotBlank
    private String sessionId;

    /** 用户输入提示词。 */
    private String promptText;

    /** SQL 文本。 */
    @NotBlank
    private String sqlText;

    /** 执行耗时（毫秒）。 */
    private Long executionMs;

    /** 是否执行成功。 */
    @NotNull
    private Boolean success;
}
