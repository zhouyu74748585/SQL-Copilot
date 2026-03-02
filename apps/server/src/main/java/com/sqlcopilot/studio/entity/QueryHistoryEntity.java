package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class QueryHistoryEntity {
    private Long id;
    private Long connectionId;
    private String sessionId;
    private String promptText;
    private String sqlText;
    private Long executionMs;
    private Integer successFlag;
    private Long createdAt;
}
