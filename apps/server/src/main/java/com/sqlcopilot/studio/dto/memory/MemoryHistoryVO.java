package com.sqlcopilot.studio.dto.memory;

import lombok.Data;

import java.util.List;

/** 历史 SQL 记忆响应对象。 */
@Data
public class MemoryHistoryVO {

    /** 查询历史主键 ID。 */
    private Long historyId;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 会话 ID。 */
    private String sessionId;

    /** 自然语言问题。 */
    private String promptText;

    /** SQL 文本。 */
    private String sqlText;

    /** 数据库名称。 */
    private String databaseName;

    /** 语义摘要。 */
    private String semanticSummary;

    /** 关联表列表。 */
    private List<String> tables;

    /** 来源类型。 */
    private String sourceType;

    /** 执行耗时（毫秒）。 */
    private Long executionMs;

    /** 创建时间戳（毫秒）。 */
    private Long createdAt;
}
