package com.sqlcopilot.studio.mapper;

import com.sqlcopilot.studio.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Insert;

public interface AuditLogMapper {

    @Insert("""
        INSERT INTO audit_log(connection_id, session_id, risk_level, sql_digest, operator_name, action, created_at)
        VALUES(#{connectionId}, #{sessionId}, #{riskLevel}, #{sqlDigest}, #{operatorName}, #{action}, #{createdAt})
        """)
    int insert(AuditLogEntity entity);
}
