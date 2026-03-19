package com.sqlcopilot.studio.dto.kv;

import lombok.Data;

/** 删除 Redis 键结果对象。 */
@Data
public class KvRedisKeyDeleteVO {

    /** 目标类型：KEY/PATH。 */
    private String targetType;

    /** 删除目标值。 */
    private String targetValue;

    /** 实际删除数量。 */
    private Long deletedCount;

    /** 结果提示信息。 */
    private String message;
}
