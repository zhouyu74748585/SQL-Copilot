package com.sqlcopilot.studio.dto.kv;

import lombok.Data;

/** KV/文档对象摘要。 */
@Data
public class KvObjectSummaryVO {

    /** 对象名称。 */
    private String objectName;

    /** 对象值类型。 */
    private String valueType;

    /** 条目数量或样本数量。 */
    private Long itemCount;

    /** 对象说明。 */
    private String description;
}
