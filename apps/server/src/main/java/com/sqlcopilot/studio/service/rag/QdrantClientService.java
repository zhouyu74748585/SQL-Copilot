package com.sqlcopilot.studio.service.rag;

import com.sqlcopilot.studio.service.rag.model.QdrantCollectionMetric;
import com.sqlcopilot.studio.service.rag.model.QdrantPoint;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;

import java.util.List;

public interface QdrantClientService {

    void ensureCollection(String collectionName, int vectorSize);

    void upsertPoints(String collectionName, List<QdrantPoint> points);

    List<QdrantScoredPoint> searchPoints(String collectionName,
                                         List<Float> vector,
                                         int limit,
                                         Long connectionId,
                                         String databaseName);

    QdrantCollectionMetric queryCollectionMetric(String collectionName, Long connectionId, String databaseName);

    void deletePointsByFilter(String collectionName, Long connectionId, String databaseName, String sessionId);
}
