package com.sqlcopilot.studio.dto.kv;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除 Redis 键/路径请求对象。 */
@Data
public class KvRedisKeyDeleteReq {

    /** 连接 ID。 */
    @NotNull
    private Long connectionId;

    /** 逻辑库名称。 */
    private String databaseName;

    /** 目标类型：KEY/PATH。 */
    @NotBlank
    private String targetType;

    /** 目标值：键名或路径前缀。 */
    @NotBlank
    private String targetValue;
}
