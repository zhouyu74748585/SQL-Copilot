package com.sqlcopilot.studio.dto.kv;

import lombok.Data;

import java.util.List;

/** KV/文档对象详情。 */
@Data
public class KvObjectDetailVO {

    /** 连接 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 对象名称。 */
    private String objectName;

    /** 对象值类型。 */
    private String valueType;

    /** 对象说明。 */
    private String description;

    /** 推荐查询模板。 */
    private String queryTemplate;

    /** 样本内容。 */
    private String sampleJson;

    /** 补充说明列表。 */
    private List<String> facts;
}
