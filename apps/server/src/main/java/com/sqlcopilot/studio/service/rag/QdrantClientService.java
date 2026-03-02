package com.sqlcopilot.studio.service.rag;

import com.sqlcopilot.studio.service.rag.model.QdrantPoint;

import java.util.List;

public interface QdrantClientService {

    void ensureCollection(String collectionName, int vectorSize);

    void upsertPoints(String collectionName, List<QdrantPoint> points);
}
