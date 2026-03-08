package com.sqlcopilot.studio.dto.knowledge;

import lombok.Data;

/** 知识中心列表查询对象。 */
@Data
public class KnowledgeListQueryReq {

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;
}
