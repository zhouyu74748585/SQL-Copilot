package com.sqlcopilot.studio.dto.kv;

import lombok.Data;

/** Redis 成员项对象。 */
@Data
public class KvRedisKeyEntryVO {

    /** 字段名或成员名。 */
    private String key;

    /** 值内容。 */
    private String value;

    /** 有序集合分值。 */
    private Double score;
}
