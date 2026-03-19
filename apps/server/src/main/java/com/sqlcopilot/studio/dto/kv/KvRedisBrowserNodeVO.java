package com.sqlcopilot.studio.dto.kv;

import lombok.Data;

/** Redis 树表格节点对象。 */
@Data
public class KvRedisBrowserNodeVO {

    /** 节点唯一键。 */
    private String nodeKey;

    /** 当前层展示名称。 */
    private String nodeName;

    /** 节点完整路径。 */
    private String fullPath;

    /** 节点类型：PATH/KEY。 */
    private String nodeType;

    /** 是否仍有下级节点。 */
    private Boolean hasChildren;

    /** Redis 完整键名；PATH 节点为空。 */
    private String objectName;

    /** Redis 值类型；仅 KEY 节点返回。 */
    private String valueType;

    /** TTL 秒数；仅 KEY 节点返回。 */
    private Long ttlSeconds;

    /** 节点说明。 */
    private String description;
}
