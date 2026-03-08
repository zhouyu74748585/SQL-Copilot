package com.sqlcopilot.studio.service.rag;

import com.sqlcopilot.studio.service.rag.model.QdrantCollectionMetric;
import com.sqlcopilot.studio.service.rag.model.QdrantPoint;
import com.sqlcopilot.studio.service.rag.model.QdrantPayloadFilter;
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

    List<QdrantScoredPoint> searchPointsByFilters(String collectionName,
                                                  List<Float> vector,
                                                  int limit,
                                                  List<QdrantPayloadFilter> filters);

    QdrantCollectionMetric queryCollectionMetric(String collectionName, Long connectionId, String databaseName);

    QdrantCollectionMetric queryCollectionMetricByFilters(String collectionName, List<QdrantPayloadFilter> filters);

    void deletePointsByFilter(String collectionName, Long connectionId, String databaseName, String sessionId);

    void deletePointsByFilters(String collectionName, List<QdrantPayloadFilter> filters);

    void recreateCollection(String collectionName, int vectorSize);
}
