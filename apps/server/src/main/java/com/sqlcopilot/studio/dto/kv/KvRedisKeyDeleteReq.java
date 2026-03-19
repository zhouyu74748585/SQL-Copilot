package com.sqlcopilot.studio.dto.kv;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除 Redis 键请求对象。 */
@Data
public class KvRedisKeyDeleteReq {

    /** 连接 ID。 */
    @NotNull
    private Long connectionId;

    /** 逻辑库名称。 */
    private String databaseName;

    /** 删除目标类型：KEY/PATH。 */
    private String targetType;

    /** 删除目标值：键名或路径前缀。 */
    private String targetValue;

    /** 兼容旧请求结构的键名字段。 */
    private String keyName;
}
