package com.sqlcopilot.studio.service.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** Qdrant 点位对象。 */
@Data
@AllArgsConstructor
public class QdrantPoint {

    /** 点位 ID（建议使用稳定 UUID）。 */
    private String id;

    /** 向量值。 */
    private List<Float> vector;

    /** 业务元数据。 */
    private Object payload;
}
