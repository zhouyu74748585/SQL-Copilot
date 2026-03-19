package com.sqlcopilot.studio.dto.kv;

import lombok.Data;

/** Redis 删除结果。 */
@Data
public class KvRedisKeyDeleteVO {

    private String targetType;

    private String targetValue;

    private Integer deletedCount;

    private String message;
}
