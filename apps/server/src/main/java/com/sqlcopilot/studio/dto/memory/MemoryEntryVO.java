package com.sqlcopilot.studio.dto.memory;

import lombok.Data;

import java.util.List;

/** 长期记忆响应对象。 */
@Data
public class MemoryEntryVO {

    /** 长期记忆主键 ID。 */
    private Long id;

    /** 记忆作用域。 */
    private String scope;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 记忆标题。 */
    private String title;

    /** 记忆摘要正文。 */
    private String summary;

    /** 来源类型：AUTO_SESSION / PROMOTED_SQL / MANUAL。 */
    private String sourceType;

    /** 来源会话 ID。 */
    private String sourceSessionId;

    /** 来源历史记录 ID 列表。 */
    private List<Long> sourceHistoryIds;

    /** 命中次数。 */
    private Long hitCount;

    /** 最近命中时间戳（毫秒）。 */
    private Long lastUsedAt;

    /** 创建时间戳（毫秒）。 */
    private Long createdAt;

    /** 更新时间戳（毫秒）。 */
    private Long updatedAt;
}
