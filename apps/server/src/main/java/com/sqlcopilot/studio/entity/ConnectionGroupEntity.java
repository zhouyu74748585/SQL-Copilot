package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class ConnectionGroupEntity {
    private Long id;
    private String name;
    private Integer sortOrder;
    private Long createdAt;
    private Long updatedAt;
    private Integer connectionCount;
}
