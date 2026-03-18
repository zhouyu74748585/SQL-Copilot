package com.sqlcopilot.studio.dto.kv;

import lombok.Data;

/** 保存 Redis 键结果对象。 */
@Data
public class KvRedisKeySaveVO {

    /** 是否成功。 */
    private Boolean success;

    /** 提示消息。 */
    private String message;

    /** 键名。 */
    private String keyName;

    /** 键类型。 */
    private String valueType;
}
