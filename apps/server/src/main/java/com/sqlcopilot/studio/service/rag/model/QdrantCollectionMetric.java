package com.sqlcopilot.studio.service.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Qdrant 集合统计信息。 */
@Data
@AllArgsConstructor
public class QdrantCollectionMetric {

    /** 集合名称。 */
    private String collectionName;

    /** 向量维度。 */
    private Integer vectorDimension;

    /** 按连接+数据库过滤后的点位数量。 */
    private Long pointCount;
}
