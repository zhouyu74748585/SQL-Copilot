package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class MemoryEntryEntity {
    private Long id;
    private String scope;
    private Long connectionId;
    private String databaseName;
    private String title;
    private String summary;
    private String sourceType;
    private String sourceSessionId;
    private String sourceHistoryIdsJson;
    private Long hitCount;
    private Long lastUsedAt;
    private Long createdAt;
    private Long updatedAt;
}
