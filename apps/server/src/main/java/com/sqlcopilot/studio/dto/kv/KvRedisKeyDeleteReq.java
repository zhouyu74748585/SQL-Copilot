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

    /** 键名。 */
    @NotBlank
    private String keyName;
}
