package com.sqlcopilot.studio.dto.editor;

import lombok.Data;

/** 会话历史摘要响应对象。 */
@Data
public class QueryHistorySessionVO {

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 会话 ID。 */
    private String sessionId;

    /** 会话标题。 */
    private String title;

    /** 会话创建时间戳（毫秒）。 */
    private Long createdAt;

    /** 会话最近更新时间戳（毫秒）。 */
    private Long updatedAt;

    /** 会话记录条数。 */
    private Long messageCount;
}
