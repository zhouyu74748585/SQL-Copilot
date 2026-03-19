package com.sqlcopilot.studio.dto.kv;

import lombok.Data;

/** Redis 浏览节点。 */
@Data
public class KvRedisBrowserNodeVO {

    private String nodeKey;

    private String nodeName;

    private String fullPath;

    private String nodeType;

    private Boolean hasChildren;

    private String objectName;

    private String valueType;

    private Long ttlSeconds;

    private String description;
}
