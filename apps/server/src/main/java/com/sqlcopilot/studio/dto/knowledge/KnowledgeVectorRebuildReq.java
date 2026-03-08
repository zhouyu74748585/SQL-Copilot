package com.sqlcopilot.studio.dto.knowledge;

import lombok.Data;

/** 知识向量重建请求对象。 */
@Data
public class KnowledgeVectorRebuildReq {

    /** 当前连接主键 ID。 */
    private Long connectionId;

    /** 当前数据库名称。 */
    private String databaseName;
}
