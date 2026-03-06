package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class QueryHistoryEntity {
    private Long id;
    private Long connectionId;
    private String sessionId;
    private String promptText;
    private String sqlText;
    private String historyType;
    private String actionType;
    private String assistantContent;
    private String databaseName;
    private String chartConfigJson;
    private String chartImageCacheKey;
    private String structuredContextJson;
    private Integer tokenEstimate;
    private Integer memoryEnabled;
    private Long executionMs;
    private Integer successFlag;
    private Long createdAt;
}
