package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class SavedQueryEntity {
    private Long id;
    private Long connectionId;
    private String databaseName;
    private String title;
    private String sqlText;
    private Long createdAt;
    private Long updatedAt;
}
