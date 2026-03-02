package com.sqlcopilot.studio.entity;

import lombok.Data;

@Data
public class AuditLogEntity {
    private Long id;
    private Long connectionId;
    private String sessionId;
    private String riskLevel;
    private String sqlDigest;
    private String operatorName;
    private String action;
    private Long createdAt;
}
