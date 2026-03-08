package com.sqlcopilot.studio.dto.knowledge;

import lombok.Data;

/** 术语响应对象。 */
@Data
public class KnowledgeTermVO {

    /** 术语主键 ID。 */
    private Long id;

    /** 作用域：GLOBAL/CONNECTION/DATABASE。 */
    private String scope;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 术语名称。 */
    private String term;

    /** 术语说明。 */
    private String description;

    /** 创建时间戳（毫秒）。 */
    private Long createdAt;

    /** 更新时间戳（毫秒）。 */
    private Long updatedAt;
}
