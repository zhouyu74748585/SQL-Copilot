package com.sqlcopilot.studio.dto.kv;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 保存 Redis 键请求对象。 */
@Data
public class KvRedisKeySaveReq {

    /** 连接 ID。 */
    @NotNull
    private Long connectionId;

    /** 逻辑库名称。 */
    private String databaseName;

    /** 键名。 */
    @NotBlank
    private String keyName;

    /** 键类型：string/hash/list/set/zset。 */
    @NotBlank
    private String valueType;

    /** TTL 秒数，-1 表示永不过期。 */
    private Long ttlSeconds;

    /** 字符串值。 */
    private String stringValue;

    /** 结构化成员集合。 */
    private List<KvRedisKeyEntryVO> entries;
}
