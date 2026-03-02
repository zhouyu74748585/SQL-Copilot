package com.sqlcopilot.studio.service.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/** Qdrant 向量检索命中结果。 */
@Data
@AllArgsConstructor
public class QdrantScoredPoint {

    /** 点位 ID。 */
    private String id;

    /** 相似度得分。 */
    private Double score;

    /** 元数据载荷。 */
    private Map<String, Object> payload;
}
