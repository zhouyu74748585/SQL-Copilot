package com.sqlcopilot.studio.dto.editor;

import lombok.Data;

/** 查询历史响应对象。 */
@Data
public class QueryHistoryVO {

    /** 历史记录 ID。 */
    private Long id;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 会话 ID。 */
    private String sessionId;

    /** 自然语言输入。 */
    private String promptText;

    /** SQL 文本。 */
    private String sqlText;

    /** 执行耗时（毫秒）。 */
    private Long executionMs;

    /** 是否执行成功。 */
    private Boolean success;

    /** 创建时间戳（毫秒）。 */
    private Long createdAt;
}
